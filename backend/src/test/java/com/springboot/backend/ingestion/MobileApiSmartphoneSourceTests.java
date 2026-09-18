package com.springboot.backend.ingestion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SourceContext.get(URI) always makes a real HTTP call with no seam to fake
 * it, so the fetch-and-translate path itself needs a live API key and a real
 * (or demo-script) run to verify end to end — see docs/ingestion.md's demo
 * script pattern. Everything faked-out here is deliberately the part that
 * doesn't need network: safe behaviour with no API key configured yet.
 * MobileApiFieldExtractorTests covers all the actual parsing logic.
 */
class MobileApiSmartphoneSourceTests {
    @Test void sourceIdIsStableAndValidForTheRegistry() {
        var source = new MobileApiSmartphoneSource("", "");
        assertEquals("mobileapi-smartphone", source.sourceId());
        assertTrue(source.sourceId().matches("[a-z0-9-]{1,80}"));
    }

    @Test void noApiKeyConfiguredEmitsNothingRatherThanFailing() throws Exception {
        var source = new MobileApiSmartphoneSource("", "");
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
