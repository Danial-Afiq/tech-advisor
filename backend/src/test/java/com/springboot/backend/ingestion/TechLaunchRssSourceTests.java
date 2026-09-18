package com.springboot.backend.ingestion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SourceContext.get(URI) always makes a real HTTP call with no seam to fake
 * it (same constraint noted on MobileApiSmartphoneSourceTests) — the real
 * fetch-and-translate path needs a live/demo run to verify end to end.
 * RssFeedParserTests covers all the actual parsing logic.
 */
class TechLaunchRssSourceTests {
    @Test void sourceIdIsStableAndValidForTheRegistry() {
        var source = new TechLaunchRssSource("");
        assertEquals("tech-launch-rss", source.sourceId());
        assertTrue(source.sourceId().matches("[a-z0-9-]{1,80}"));
    }

    @Test void noConfiguredFeedsEmitsNothingRatherThanFailing() throws Exception {
        var source = new TechLaunchRssSource("");
        var context = new SourceContext(Clock.systemUTC(), () -> {});
        List<Payload> emitted = new ArrayList<>();
        try {
            source.ingest(context, emitted::add);
        } finally {
            context.close();
        }
        assertTrue(emitted.isEmpty());
    }
}
