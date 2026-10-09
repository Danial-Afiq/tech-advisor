package com.springboot.backend.ingestion.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadTests {
    @Test void specificationsRequireMatchingUnitsAndValidNumbers() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of(), List.of())).validate("specs"));
        assertDoesNotThrow(() -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of())).validate("specs"));
    }
    @Test void specificationsRequireBrandAndModelNameButChipsetIsOptional() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", null, "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of())).validate("specs"));
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", " ", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of())).validate("specs"));
        assertDoesNotThrow(() -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", null,
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of())).validate("specs"));
    }
    @Test void specificationsAcceptRealStorageTiersButRejectNonPositiveOnes() {
        assertDoesNotThrow(() -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", null,
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of(256, 512, 1024)))
                .validate("specs"));
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", null,
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"), List.of(0))).validate("specs"));
    }
    @Test void sourceCannotEmitUnderAnotherSourcesIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("other", "1", Instant.now(),
                new Payload.Price("phone", null, BigDecimal.ONE, "SGD")).validate("expected"));
    }
}
