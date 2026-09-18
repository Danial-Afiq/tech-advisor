package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.context.annotation.*;

@Configuration
@Profile("ingestion-demo")
public class SimulatedSources {
    @Bean IngestionSource simulatedRelease() {
        return new IngestionSource() {
            public String sourceId() { return "simulated-release"; }
            public boolean simulation() { return true; }
            public Duration cooldown() { return Duration.ZERO; }
            public void ingest(SourceContext context, Consumer<Payload> output) {
                output.accept(new Payload(sourceId(), "article-1", context.now(), new Payload.Article(
                        "demo-phone", "Demo phone announced", URI.create("https://example.com/demo-phone"),
                        context.now(), "Simulated review evidence; sentiment analysis happens downstream.")));
                output.accept(new Payload(sourceId(), "specs-1", context.now(), new Payload.Specifications(
                        "demo-phone", Map.of("battery", new BigDecimal("5000")), Map.of("battery", "mAh"))));
                output.accept(new Payload(sourceId(), "price-1", context.now(), new Payload.Price(
                        "demo-phone", new BigDecimal("999.00"), "SGD")));
            }
        };
    }
    @Bean IngestionSource simulatedFailure() {
        return new IngestionSource() {
            public String sourceId() { return "simulated-failure"; }
            public boolean simulation() { return true; }
            public Duration cooldown() { return Duration.ZERO; }
            public void ingest(SourceContext context, Consumer<Payload> output) {
                throw new IllegalStateException("Deliberate fixture failure");
            }
        };
    }
}
