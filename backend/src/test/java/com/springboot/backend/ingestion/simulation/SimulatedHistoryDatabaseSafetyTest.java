package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;

class SimulatedHistoryDatabaseSafetyTest {

    @Test
    void acceptsOnlyExplicitLoopbackPostgresUrlsRegardlessOfDatabaseName() {
        assertDoesNotThrow(() -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql://localhost:5433/techadvisor"));
        assertDoesNotThrow(() -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql://127.0.0.1:5433/arbitrary_name"));
        assertDoesNotThrow(() -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql://[::1]:5433/techadvisor"));
    }

    @Test
    void rejectsHostedRemoteAndAmbiguousConnectionsEvenWhenDatabaseLooksLocal() {
        assertThrows(IllegalStateException.class, () -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql://ep-example.neon.tech/techadvisor"));
        assertThrows(IllegalStateException.class, () -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql://192.168.1.20:5432/techadvisor_demo"));
        assertThrows(IllegalStateException.class, () -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:postgresql:techadvisor"));
        assertThrows(IllegalStateException.class, () -> SimulatedHistoryService.requireLocalPostgres(
                "jdbc:h2:mem:techadvisor"));
    }

    @Test
    void everySimulatorEntryPointIsRestrictedToTheDemoProfile() {
        assertDemoProfile(SimulatedHistoryGenerator.class);
        assertDemoProfile(SimulatedHistoryService.class);
        assertDemoProfile(SimulatedMarketChangeService.class);
        assertDemoProfile(SimulatedHistoryController.class);
    }

    private static void assertDemoProfile(Class<?> type) {
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(type, Profile.class);
        assertTrue(profile != null && Arrays.asList(profile.value()).contains("ingestion-demo"),
                type.getSimpleName() + " must remain disabled outside ingestion-demo");
    }
}
