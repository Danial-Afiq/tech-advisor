package com.springboot.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.springboot.backend.dto.ApiErrorResponse;
import com.springboot.backend.ingestion.IngestionController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiSchemaModelTests {

    @Test
    void errorAndRequestSchemasExposeTheirDocumentedValues() {
        var apiError = new ApiErrorResponse("Device not found");
        var request = new IngestionController.Request(
                List.of("simulated-prices"),
                "Week 7 demonstration");
        var ingestionError = new IngestionController.ErrorResponse("Invalid request");

        assertAll(
                () -> assertEquals("Device not found", apiError.error()),
                () -> assertEquals(List.of("simulated-prices"), request.sources()),
                () -> assertEquals("Week 7 demonstration", request.reason()),
                () -> assertEquals("Invalid request", ingestionError.message()));
    }

    @Test
    void ingestionResponseSchemasExposeTheirDocumentedValues() {
        var anchor = Instant.parse("2026-09-28T00:00:00Z");
        var nextScheduledAt = anchor.plusSeconds(86_400);
        var nextAllowedAt = anchor.plusSeconds(3_600);
        var session = new IngestionController.SessionResponse("admin@example.com");
        var schedule = new IngestionController.ScheduleResponse(
                true,
                1,
                24,
                anchor,
                nextScheduledAt,
                "run-123");
        var source = new IngestionController.SourceResponse(
                "simulated-prices",
                true,
                false,
                nextAllowedAt);

        assertAll(
                () -> assertEquals("admin@example.com", session.username()),
                () -> assertTrue(schedule.enabled()),
                () -> assertEquals(1, schedule.intervalDays()),
                () -> assertEquals(24, schedule.intervalHours()),
                () -> assertEquals(anchor, schedule.anchor()),
                () -> assertEquals(nextScheduledAt, schedule.nextScheduledAt()),
                () -> assertEquals("run-123", schedule.activeRunId()),
                () -> assertEquals("simulated-prices", source.sourceId()),
                () -> assertTrue(source.enabled()),
                () -> assertFalse(source.simulation()),
                () -> assertEquals(nextAllowedAt, source.nextAllowedAt()));
    }
}
