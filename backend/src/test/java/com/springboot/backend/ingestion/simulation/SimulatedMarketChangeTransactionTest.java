package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.springboot.backend.marketevent.MarketEventService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false"
})
@ActiveProfiles("ingestion-demo")
@DirtiesContext
class SimulatedMarketChangeTransactionTest {

    private static final String BRAND = "SimulatedMarketChangeTransactionTest";

    @Autowired SimulatedMarketChangeService changes;
    @Autowired JdbcTemplate db;
    @MockitoBean MarketEventService marketEvents;

    private long productId;

    @BeforeEach
    void setUp() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
        db.update("DELETE FROM products WHERE brand = ?", BRAND);
        productId = db.queryForObject("""
                INSERT INTO products(brand, model_name, category, status)
                VALUES (?, 'Phone', 'SMARTPHONE', 'VERIFIED') RETURNING id
                """, Long.class, BRAND);
        db.update("""
                INSERT INTO price_history(product_id, price, currency, source, observed_at)
                VALUES (?, 999.00, 'SGD', 'real-price', ?)
                """, productId, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void observationRollsBackWhenMarketEventRecordingFails() {
        when(marketEvents.record(any())).thenThrow(new IllegalStateException("event insert failed"));

        assertThrows(IllegalStateException.class, () -> changes.injectPrice(
                new SimulatedMarketChangeService.PriceCommand(
                        productId, new BigDecimal("899.00"), "rollback-test")));

        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM price_history WHERE product_id = ?", Integer.class, productId));
        assertEquals("real-price", db.queryForObject(
                "SELECT source FROM price_history WHERE product_id = ?", String.class, productId));
    }
}
