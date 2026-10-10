package com.springboot.backend.marketevent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One persisted {@code market_events} row.
 *
 * @param productId nullable: an event may describe the market rather than one product,
 *                  and such an event has nothing to re-evaluate
 */
@Schema(name = "MarketEventResponse", description = "A recorded market event.")
public record MarketEvent(
        @Schema(example = "42") long id,
        @Schema(example = "17", nullable = true) Long productId,
        MarketEventType eventType,
        @Schema(example = "Galaxy S25 drops to S$1099") String title,
        @Schema(nullable = true) String description,
        @Schema(nullable = true, example = "{\"price\": 1199}") Map<String, Object> oldValue,
        @Schema(nullable = true, example = "{\"price\": 1099}") Map<String, Object> newValue,
        @Schema(example = "admin", nullable = true) String source,
        OffsetDateTime detectedAt) {}
