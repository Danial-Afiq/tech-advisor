package com.springboot.backend.recommendation.trigger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The manual trigger endpoints and admin market-event entry: ADMIN only,
 * {@code 202} without waiting, documented in the OpenAPI contract. The services
 * behind them are mocked; their behaviour is tested elsewhere.
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "admin.email=test-admin@example.com",
        "admin.password=test-admin-password"
})
class TriggerAdminEndpointsTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean RecommendationTriggerService triggers;
    @MockitoBean MarketEventService marketEvents;

    private MockMvc mvc;
    private User user;

    @DynamicPropertySource
    static void jwtConfiguration(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString(secret));
        registry.add("jwt.expiration-seconds", () -> 3600L);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        user = userRepository.save(new User("trigger-user-" + UUID.randomUUID() + "@example.com", "unused", "USER"));
    }

    @AfterEach
    void tearDown() {
        userRepository.delete(user);
    }

    @Test
    void anAdminQueuesAMarketEventRunAndGets202() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/triggers/market-events/42").header("Authorization", admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trigger").value("MARKET_EVENT"))
                .andExpect(jsonPath("$.targetId").value(42))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(triggers).queueMarketEvent(42L);
    }

    @Test
    void anAdminQueuesADeviceRunAndGets202() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/triggers/user-devices/9").header("Authorization", admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trigger").value("DEVICE_INVENTORY"));

        verify(triggers).queueDevice(9L);
    }

    @Test
    void anUnknownTargetIs404() throws Exception {
        doThrow(new ResourceNotFoundException("Market event 404 not found")).when(triggers).queueMarketEvent(404L);

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/triggers/market-events/404").header("Authorization", admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aUserCannotFireTriggersOrRecordEvents() throws Exception {
        String token = "Bearer " + jwtService.generateToken(user);

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/triggers/market-events/42").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/triggers/user-devices/9").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/market-events").header("Authorization", token)
                        .contentType("application/json")
                        .content("{\"productId\": 1, \"eventType\": \"PRICE_CHANGE\", \"title\": \"x\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(triggers, marketEvents);
    }

    @Test
    void anAdminRecordsAMarketEvent() throws Exception {
        when(marketEvents.record(any())).thenReturn(new MarketEvent(
                7L, 1L, MarketEventType.PRICE_CHANGE, "Galaxy S25 drops", null, null, null, "admin", OffsetDateTime.now()));

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/market-events").header("Authorization", admin())
                        .contentType("application/json")
                        .content("{\"productId\": 1, \"eventType\": \"PRICE_CHANGE\", \"title\": \" Galaxy S25 drops \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));

        verify(marketEvents).record(argThatTitle("Galaxy S25 drops"));
    }

    @Test
    void anInvalidMarketEventIs400() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/market-events").header("Authorization", admin())
                        .contentType("application/json")
                        .content("{\"productId\": 1, \"title\": \"no type\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(marketEvents);
    }

    @Test
    void theEndpointsAreInTheOpenApiContract() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/admin/triggers/market-events/{marketEventId}']['post']['responses']['202']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/triggers/market-events/{marketEventId}']['post']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/api/admin/triggers/user-devices/{userDeviceId}']['post']['responses']['202']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/market-events']['post']['responses']['201']").exists());
    }

    private String admin() {
        return "Bearer " + jwtService.generateToken(
                userRepository.findByEmailIgnoreCase("test-admin@example.com").orElseThrow());
    }

    private static MarketEventService.NewMarketEvent argThatTitle(String title) {
        return org.mockito.ArgumentMatchers.argThat(e -> title.equals(e.title()) && "admin".equals(e.source()));
    }
}
