package com.springboot.backend.ingestion.techapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TechApiBenchmarkSourceTests {
    private static final String DATA_SHA = "a".repeat(40);
    private static final String SOC_SHA = "b".repeat(40);
    private static final String SOC_PATH = "qualcomm/2024/snapdragon-8-elite.json";

    TechApiBenchmarkRepository repository = mock(TechApiBenchmarkRepository.class);
    SourceContext context = mock(SourceContext.class);
    TechApiBenchmarkSource source = new TechApiBenchmarkSource(repository, "", 20);

    @BeforeEach
    void setup() throws Exception {
        when(context.remainingRequests()).thenReturn(10);
        when(context.now()).thenReturn(Instant.parse("2026-10-21T00:00:00Z"));
        when(context.get(any(URI.class))).thenAnswer(invocation -> response(invocation.getArgument(0)));
    }

    @Test
    void parsesPhoneToSocAndEmitsRawGeekbenchScores() throws Exception {
        var phone = phone(11, "Samsung", "Galaxy S25", "Qualcomm Snapdragon 8 Elite");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        List<Payload> emitted = new ArrayList<>();

        source.ingest(context, emitted::add);

        assertEquals(2, emitted.size());
        var single = (Payload.Benchmark) emitted.get(0).body();
        var multi = (Payload.Benchmark) emitted.get(1).body();
        assertEquals(TechApiBenchmarkSource.GEEKBENCH_SINGLE, single.name());
        assertEquals("3200", single.score().toPlainString());
        assertEquals(TechApiBenchmarkSource.GEEKBENCH_MULTI, multi.name());
        assertEquals("10000", multi.score().toPlainString());
        assertTrue(single.higherIsBetter());
        assertTrue(single.provenance().contains("representative processor-level"));
        assertEquals(Instant.parse("2026-05-28T23:32:19Z"), emitted.getFirst().observedAt());
        verify(repository).checkpoint(11);
        verify(repository).restartCycle();
    }

    @Test
    void phonesSharingAProcessorReuseTheFetchedSocRecord() throws Exception {
        var samsung = phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        var onePlus = phone(12, "OnePlus", "OnePlus 13", "Qualcomm Snapdragon 8 Elite");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(samsung, onePlus), true));
        List<Payload> emitted = new ArrayList<>();

        source.ingest(context, emitted::add);

        assertEquals(4, emitted.size());
        verify(context, times(1)).get(URI.create(
                "https://raw.githubusercontent.com/GetTechAPI/TechAPI/main/data/soc/" + SOC_PATH));
        verify(repository).checkpoint(11);
        verify(repository).checkpoint(12);
        verify(repository, never()).restartCycle();
    }

    @Test
    void missingUnknownAndMismatchedChipsetsAreSkippedWithoutCrashing() throws Exception {
        var unknown = phone(11, "Samsung", "Galaxy S25", "Different Processor");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(unknown), false));
        List<Payload> emitted = new ArrayList<>();

        assertDoesNotThrow(() -> source.ingest(context, emitted::add));

        assertTrue(emitted.isEmpty());
        verify(repository).checkpoint(11);
    }

    @Test
    void ambiguousSocPathsAreSkippedRatherThanGuessed() throws Exception {
        var phone = phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        when(context.get(any(URI.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.toString().endsWith(SOC_SHA + "?recursive=1")) return bytes("""
                    {"truncated":false,"tree":[
                      {"path":"qualcomm/2024/snapdragon-8-elite.json","type":"blob"},
                      {"path":"qualcomm/2025/snapdragon-8-elite.json","type":"blob"}
                    ]}
                    """);
            return response(uri);
        });
        List<Payload> emitted = new ArrayList<>();

        source.ingest(context, emitted::add);

        assertTrue(emitted.isEmpty());
        verify(repository).checkpoint(11);
    }

    @Test
    void invalidBenchmarkValuesAreNotEmitted() throws Exception {
        var missing = TechApiBenchmarkSource.parseSoc(json("""
                {"slug":"chip","verified":true,"geekbench_single":0,"geekbench_multi":"unknown"}
                """), "chip", Instant.EPOCH);
        assertTrue(missing.isEmpty());

        var singleOnly = TechApiBenchmarkSource.parseSoc(json("""
                {"slug":"chip","verified":true,"geekbench_single":1234}
                """), "chip", Instant.EPOCH).orElseThrow();
        assertEquals("1234", singleOnly.single().toPlainString());
        assertNull(singleOnly.multi());
    }

    @Test
    void parsesVerifiedSmartphoneSocReferencesAndRejectsIncompleteOnes() throws Exception {
        var parsed = TechApiBenchmarkSource.parsePhone(json(phoneJson(
                "Samsung", "Galaxy S25", "snapdragon-8-elite", "Snapdragon 8 Elite"))).orElseThrow();
        assertEquals("snapdragon-8-elite", parsed.socSlug());

        assertTrue(TechApiBenchmarkSource.parsePhone(json("""
                {"name":"Galaxy S25","brand":{"name":"Samsung"},"verified":true}
                """)).isEmpty());
    }

    @Test
    void matchingSupportsAppleNamesWithoutGuessingMissingOrDifferentChipsets() {
        var apple = phone(21, "Apple", "Apple iPhone 16 Pro", "Apple A18 Pro");
        var reference = new TechApiBenchmarkSource.PhoneReference(
                "Apple", "iPhone 16 Pro", "apple-a18-pro", "Apple A18 Pro");

        assertTrue(TechApiBenchmarkSource.matches(apple, reference));
        assertFalse(TechApiBenchmarkSource.matches(
                phone(22, "Apple", "Apple iPhone 16 Pro", null), reference));
        assertFalse(TechApiBenchmarkSource.matches(
                phone(23, "Apple", "Apple iPhone 16 Pro", "Apple A17 Pro"), reference));
    }

    @Test
    void requestFailureStopsTheRunWithoutAdvancingTheCursor() throws Exception {
        var phone = phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        when(context.get(any(URI.class))).thenThrow(new SourceContext.TransportFailure());

        assertThrows(SourceContext.TransportFailure.class,
                () -> source.ingest(context, ignored -> {}));
        verify(repository, never()).checkpoint(anyLong());
    }

    @Test
    void exhaustedRequestBudgetLeavesTheCurrentPhoneForTheNextRun() throws Exception {
        var phone = phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        when(repository.nextPhones(20)).thenReturn(
                new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        when(context.remainingRequests()).thenReturn(10, 0);

        source.ingest(context, ignored -> {});

        verify(repository, never()).checkpoint(anyLong());
    }

    private byte[] response(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/trees/main")) return bytes("""
                {"tree":[{"path":"data","type":"tree","sha":"%s"}]}
                """.formatted(DATA_SHA));
        if (value.endsWith("/trees/" + DATA_SHA)) return bytes("""
                {"tree":[{"path":"soc","type":"tree","sha":"%s"}]}
                """.formatted(SOC_SHA));
        if (value.endsWith("/trees/" + SOC_SHA + "?recursive=1")) return bytes("""
                {"truncated":false,"tree":[{"path":"%s","type":"blob"}]}
                """.formatted(SOC_PATH));
        if (value.contains("/smartphones/galaxy-s25/")) return bytes(phoneJson(
                "Samsung", "Galaxy S25", "snapdragon-8-elite", "Snapdragon 8 Elite"));
        if (value.contains("/smartphones/13/")) return bytes("{}");
        if (value.contains("/smartphones/oneplus-13/")) return bytes(phoneJson(
                "OnePlus", "OnePlus 13", "snapdragon-8-elite", "Snapdragon 8 Elite"));
        if (value.endsWith("/data/soc/" + SOC_PATH)) return bytes("""
                {"slug":"snapdragon-8-elite","verified":true,
                 "geekbench_single":3200,"geekbench_multi":10000,
                 "updated_at":"2026-05-28T23:32:19"}
                """);
        fail("Unexpected URL " + value);
        return new byte[0];
    }

    private static String phoneJson(String brand, String model, String slug, String socName) {
        return """
                {"name":"%s","brand":{"name":"%s"},
                 "soc":{"slug":"%s","name":"%s"},"verified":true}
                """.formatted(model, brand, slug, socName);
    }

    private static TechApiBenchmarkRepository.PhoneTarget phone(
            long id, String brand, String model, String chipset) {
        return new TechApiBenchmarkRepository.PhoneTarget(id, brand, model, chipset);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
    }
}
