package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadTests {
    @Test void specificationsRequireMatchingUnitsAndValidNumbers() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of())).validate("specs"));
        assertDoesNotThrow(() -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"))).validate("specs"));
    }
    @Test void specificationsRequireBrandAndModelNameButChipsetIsOptional() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", null, "Model", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"))).validate("specs"));
        assertThrows(IllegalArgumentException.class, () -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", " ", "Chipset",
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"))).validate("specs"));
        assertDoesNotThrow(() -> new Payload("specs", "1", Instant.now(),
                new Payload.Specifications("phone", "Brand", "Model", null,
                        Map.of("battery", BigDecimal.ONE), Map.of("battery", "mAh"))).validate("specs"));
    }
    @Test void sourceCannotEmitUnderAnotherSourcesIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("other", "1", Instant.now(),
                new Payload.Price("phone", BigDecimal.ONE, "SGD")).validate("expected"));
    }
}
