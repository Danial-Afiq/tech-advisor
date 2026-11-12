package com.springboot.backend.ingestion.simulation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springboot.backend.marketevent.MarketEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false"
})
@ActiveProfiles("ingestion-demo")
@DirtiesContext
class SimulatedHistoryControllerTest {

    @Autowired WebApplicationContext web;
    @MockitoBean SimulatedHistoryService service;
    @MockitoBean SimulatedMarketChangeService changes;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void adminCreatesHistoryAndPercentagesAreConvertedToFractions() throws Exception {
        when(service.generate(any())).thenReturn(result(false));

        mvc.perform(post("/api/admin/simulation/history")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("simulated-history:812:abcdef0123456789"))
                .andExpect(jsonPath("$.priceRowsInserted").value(12))
                .andExpect(jsonPath("$.benchmarkRowsInserted").value(12));

        verify(service).generate(org.mockito.ArgumentMatchers.argThat(command ->
                command.productId() == 812L
                        && command.seed() == 2037L
                        && command.priceVariation().compareTo(new BigDecimal("0.15")) == 0
                        && command.benchmarkVariation().compareTo(new BigDecimal("0.03")) == 0));
    }

    @Test
    void identicalScenarioReturnsOkAndUserOrInvalidRequestsAreRejected() throws Exception {
        when(service.generate(any())).thenReturn(result(true));

        mvc.perform(post("/api/admin/simulation/history")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyGenerated").value(true));

        mvc.perform(post("/api/admin/simulation/history")
                        .with(user("member").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/simulation/history")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"observationCount\":12", "\"observationCount\":1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endpointIsDocumentedOnlyWhenDemoProfileIsActive() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/admin/simulation/history']['post']['responses']['201']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/simulation/changes/price']['post']['responses']['201']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/admin/simulation/changes/benchmark']['post']['responses']['201']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/admin/simulation/history']['post']['security'][0]['bearerAuth']")
                        .isArray());
    }

    @Test
    void adminCanInjectIdempotentPriceAndBenchmarkChanges() throws Exception {
        when(changes.injectPrice(any())).thenReturn(changeResult(MarketEventType.PRICE_CHANGE, false));
        when(changes.injectBenchmark(any())).thenReturn(changeResult(MarketEventType.BENCHMARK_UPDATE, true));

        mvc.perform(post("/api/admin/simulation/changes/price")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":812,"newPrice":899.00,"idempotencyKey":"price-drop-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("PRICE_CHANGE"));

        mvc.perform(post("/api/admin/simulation/changes/benchmark")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":812,"benchmarkName":"Geekbench 6 Multi-Core",
                                 "newScore":8500,"idempotencyKey":"benchmark-update-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("BENCHMARK_UPDATE"))
                .andExpect(jsonPath("$.alreadyApplied").value(true));

        verify(changes).injectPrice(org.mockito.ArgumentMatchers.argThat(command ->
                command.productId() == 812L
                        && command.newPrice().compareTo(new BigDecimal("899.00")) == 0
                        && command.idempotencyKey().equals("price-drop-1")));
        verify(changes).injectBenchmark(org.mockito.ArgumentMatchers.argThat(command ->
                command.productId() == 812L
                        && command.benchmarkName().equals("Geekbench 6 Multi-Core")
                        && command.newScore().compareTo(new BigDecimal("8500")) == 0));
    }

    @Test
    void usersCannotInjectChangesAndInvalidKeysAreRejected() throws Exception {
        mvc.perform(post("/api/admin/simulation/changes/price")
                        .with(user("member").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":812,"newPrice":899.00,"idempotencyKey":"price-drop-1"}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/simulation/changes/price")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":812,"newPrice":899.00,"idempotencyKey":"contains spaces"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static SimulatedHistoryService.Result result(boolean alreadyGenerated) {
        return new SimulatedHistoryService.Result(
                812L,
                "SMARTPHONE",
                "simulated-history:812:abcdef0123456789",
                alreadyGenerated ? 0 : 12,
                alreadyGenerated ? 0 : 12,
                alreadyGenerated ? 12 : 0,
                alreadyGenerated ? 12 : 0,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-12-01T00:00:00Z"),
                alreadyGenerated);
    }

    private static SimulatedMarketChangeService.Result changeResult(
            MarketEventType type,
            boolean alreadyApplied) {
        return new SimulatedMarketChangeService.Result(
                812L,
                "SMARTPHONE",
                type,
                "simulated-change:test",
                91L,
                Map.of("value", 1),
                Map.of("value", 2),
                Instant.parse("2026-11-10T00:00:00Z"),
                alreadyApplied);
    }

    private static String validRequest() {
        return """
                {
                  "productId":812,
                  "startAt":"2025-01-01T00:00:00Z",
                  "observationCount":12,
                  "intervalDays":30,
                  "seed":2037,
                  "maxPriceVariationPercent":15.00,
                  "maxBenchmarkVariationPercent":3.00
                }
                """;
    }
}
