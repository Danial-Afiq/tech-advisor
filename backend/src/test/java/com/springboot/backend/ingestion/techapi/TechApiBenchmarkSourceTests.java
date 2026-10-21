package com.springboot.backend.ingestion.techapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TechApiBenchmarkSourceTests {
    private static final String DATA_SHA = "a".repeat(40);
    private static final String BASE_SHA = "b".repeat(40);
    private static final String CURRENT_SHA = "c".repeat(40);
    private static final String BLOB_ONE = "d".repeat(40);
    private static final String BLOB_TWO = "e".repeat(40);
    private static final String PATH_ONE = "qualcomm/2024/snapdragon-8-elite.json";
    private static final String PATH_TWO = "samsung/2024/exynos-2400.json";

    TechApiBenchmarkRepository repository = mock(TechApiBenchmarkRepository.class);
    SourceContext context = mock(SourceContext.class);
    TechApiIngestionState state = new TechApiIngestionState();

    @BeforeEach
    void setup() throws Exception {
        when(repository.state()).thenReturn(state);
        when(repository.maximumEligibleProductId()).thenReturn(50L);
        when(repository.phonesThrough(anyLong())).thenReturn(List.of());
        when(repository.allPhones()).thenReturn(List.of());
        when(repository.nextPhones(anyLong(), anyInt()))
                .thenReturn(new TechApiBenchmarkRepository.Batch(List.of(), false));
        when(context.now()).thenReturn(Instant.parse("2026-10-21T00:00:00Z"));
        when(context.get(any(URI.class))).thenAnswer(call -> response(call.getArgument(0)));
    }

    @Test
    void sizesItsNetworkBudgetToTheConfiguredBatch() {
        var source = source(20);
        assertEquals(java.time.Duration.ZERO, source.cooldown());
        assertEquals(64, source.requestLimit());
        assertEquals(java.time.Duration.ofSeconds(94), source.executionBudget());
        assertEquals(java.time.Duration.ofDays(14), source.scheduleInterval().orElseThrow());
    }

    @Test
    void initialBackfillDiscoversProcessorsEmitsScoresAndMarksCompletion() throws Exception {
        var phone = phone(11, "Samsung", "Galaxy S25", "Qualcomm Snapdragon 8 Elite");
        when(repository.phonesThrough(50)).thenReturn(List.of(phone));
        List<Payload> emitted = new ArrayList<>();

        source(20).ingest(context, emitted::add);

        assertEquals(2, emitted.size());
        assertEquals("Geekbench 6 CPU Single-Core", ((Payload.Benchmark) emitted.get(0).body()).name());
        assertEquals("3200", ((Payload.Benchmark) emitted.get(0).body()).score().toPlainString());
        assertEquals("Geekbench 6 CPU Multi-Core", ((Payload.Benchmark) emitted.get(1).body()).name());
        assertTrue(state.initialBackfillComplete);
        assertEquals(BASE_SHA, state.baselineRevision);
        assertEquals(PATH_ONE, state.cursor);
        assertEquals(1, state.completedUnits);
        assertEquals(50, state.catalogueCursor);
        verify(repository, atLeast(2)).save(state);
    }

    @Test
    void boundedBackfillResumesAfterItsDurablePathWithoutResetting() throws Exception {
        state.snapshotRevision = BASE_SHA;
        state.totalUnits = 2;
        state.catalogueSnapshotMax = 50;
        when(context.get(any(URI.class))).thenAnswer(call -> twoEntryResponse(call.getArgument(0)));

        source(1).ingest(context, ignored -> {});
        assertFalse(state.initialBackfillComplete);
        assertEquals(PATH_ONE, state.cursor);
        assertEquals(1, state.completedUnits);

        source(1).ingest(context, ignored -> {});
        assertTrue(state.initialBackfillComplete);
        assertEquals(PATH_TWO, state.cursor);
        assertEquals(2, state.completedUnits);
        verify(context, times(1)).get(URI.create(blobUrl(BLOB_ONE)));
        verify(context, times(1)).get(URI.create(blobUrl(BLOB_TWO)));
    }

    @Test
    void failedRecordDoesNotAdvanceTheCheckpoint() throws Exception {
        state.snapshotRevision = BASE_SHA;
        state.totalUnits = 1;
        state.catalogueSnapshotMax = 50;
        when(context.get(URI.create(blobUrl(BLOB_ONE))))
                .thenThrow(new SourceContext.TransportFailure());

        assertThrows(SourceContext.TransportFailure.class,
                () -> source(20).ingest(context, ignored -> {}));

        assertNull(state.cursor);
        assertEquals(0, state.completedUnits);
        assertFalse(state.initialBackfillComplete);
    }

    @Test
    void unchangedIncrementalRunUsesTreeCheckpointAndWritesNothing() throws Exception {
        incrementalState(BASE_SHA, 50);
        List<Payload> emitted = new ArrayList<>();

        source(20).ingest(context, emitted::add);

        assertTrue(emitted.isEmpty());
        assertNull(state.pendingRevision);
        verify(context, never()).get(URI.create(blobUrl(BLOB_ONE)));
        verify(repository, never()).allPhones();
    }

    @Test
    void incrementalRunProcessesOnlyNewOrChangedProcessorBlobs() throws Exception {
        incrementalState(BASE_SHA, 50);
        when(repository.allPhones()).thenReturn(List.of(
                phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite")));
        when(context.get(any(URI.class))).thenAnswer(call -> changedTreeResponse(call.getArgument(0)));
        List<Payload> emitted = new ArrayList<>();

        source(20).ingest(context, emitted::add);

        assertEquals(2, emitted.size());
        assertEquals(CURRENT_SHA, state.baselineRevision);
        assertNull(state.pendingRevision);
        verify(context, times(1)).get(URI.create(blobUrl(BLOB_TWO)));
        verify(context, never()).get(URI.create(blobUrl(BLOB_ONE)));
    }

    @Test
    void modifiedProcessorCanAddAPreviouslyMissingScore() throws Exception {
        incrementalState(BASE_SHA, 50);
        when(repository.allPhones()).thenReturn(List.of(
                phone(11, "Samsung", "Galaxy S25", "Snapdragon 8 Elite")));
        when(context.get(any(URI.class))).thenAnswer(call -> {
            URI uri = call.getArgument(0);
            if (uri.toString().equals(blobUrl(BLOB_TWO))) return blob("""
                    {"slug":"snapdragon-8-elite","name":"Snapdragon 8 Elite","verified":true,
                     "geekbench_single":3200}
                    """);
            return changedSamePathResponse(uri);
        });
        List<Payload> emitted = new ArrayList<>();

        source(20).ingest(context, emitted::add);

        assertEquals(1, emitted.size());
        assertEquals("Geekbench 6 CPU Single-Core", ((Payload.Benchmark) emitted.getFirst().body()).name());
    }

    @Test
    void incrementalRunProcessesNewLocalPhonesAfterTheCatalogueCursor() throws Exception {
        incrementalState(BASE_SHA, 50);
        var phone = phone(51, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        when(repository.nextPhones(50, 20))
                .thenReturn(new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        when(repository.hasCompleteCoverage(51)).thenReturn(false);
        List<Payload> emitted = new ArrayList<>();

        source(20).ingest(context, emitted::add);

        assertEquals(2, emitted.size());
        assertEquals(51, state.catalogueCursor);
        verify(context).get(URI.create(
                "https://raw.githubusercontent.com/GetTechAPI/TechAPI/main/site/public/v1/smartphones/galaxy-s25/index.json"));
    }

    @Test
    void alreadyCoveredNewPhoneAdvancesWithoutRefetchingItsProcessor() throws Exception {
        incrementalState(BASE_SHA, 50);
        var phone = phone(51, "Samsung", "Galaxy S25", "Snapdragon 8 Elite");
        when(repository.nextPhones(50, 20))
                .thenReturn(new TechApiBenchmarkRepository.Batch(List.of(phone), false));
        when(repository.hasCompleteCoverage(51)).thenReturn(true);

        source(20).ingest(context, ignored -> {});

        assertEquals(51, state.catalogueCursor);
        verify(context, never()).get(URI.create(blobUrl(BLOB_ONE)));
    }

    @Test
    void incompleteWorkStaysDueButCompletedIncrementalCycleUsesFortnightlyCadence() {
        assertFalse(source(20).scheduleCycleComplete());

        incrementalState(BASE_SHA, 50);
        assertTrue(source(20).scheduleCycleComplete());

        state.pendingRevision = CURRENT_SHA;
        assertFalse(source(20).scheduleCycleComplete());
    }

    @Test
    void parsingAndMatchingRejectInvalidOrUncertainData() throws Exception {
        assertTrue(TechApiBenchmarkSource.parseSoc(json("""
                {"slug":"chip","verified":true,"geekbench_single":0,"geekbench_multi":"unknown"}
                """), "chip", Instant.EPOCH).isEmpty());
        assertTrue(TechApiBenchmarkSource.processorMatches(
                phone(1, "Samsung", "Galaxy", "Qualcomm Snapdragon 8 Elite"),
                "snapdragon-8-elite", "Snapdragon 8 Elite"));
        assertFalse(TechApiBenchmarkSource.processorMatches(
                phone(1, "Samsung", "Galaxy", "Different Processor"),
                "snapdragon-8-elite", "Snapdragon 8 Elite"));
        assertTrue(TechApiBenchmarkSource.parsePhone(json("""
                {"name":"Galaxy S25","brand":{"name":"Samsung"},"verified":true}
                """)).isEmpty());
    }

    private TechApiBenchmarkSource source(int limit) {
        return new TechApiBenchmarkSource(repository, "", limit);
    }

    private void incrementalState(String revision, long catalogueCursor) {
        state.phase = "INCREMENTAL";
        state.initialBackfillComplete = true;
        state.snapshotRevision = revision;
        state.baselineRevision = revision;
        state.cursor = PATH_ONE;
        state.completedUnits = 1;
        state.totalUnits = 1;
        state.catalogueCursor = catalogueCursor;
    }

    private byte[] response(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/trees/main")) return bytes("""
                {"tree":[{"path":"data","type":"tree","sha":"%s"}]}
                """.formatted(DATA_SHA));
        if (value.endsWith("/trees/" + DATA_SHA)) return bytes("""
                {"tree":[{"path":"soc","type":"tree","sha":"%s"}]}
                """.formatted(BASE_SHA));
        if (value.endsWith("/trees/" + BASE_SHA + "?recursive=1")) return tree(
                entry(PATH_ONE, BLOB_ONE));
        if (value.equals(blobUrl(BLOB_ONE))) return blob(scoreJson("snapdragon-8-elite", "Snapdragon 8 Elite"));
        if (value.contains("/smartphones/galaxy-s25/")) return bytes("""
                {"name":"Galaxy S25","brand":{"name":"Samsung"},
                 "soc":{"slug":"snapdragon-8-elite","name":"Snapdragon 8 Elite"},"verified":true}
                """);
        fail("Unexpected URL " + value);
        return new byte[0];
    }

    private byte[] twoEntryResponse(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/trees/" + BASE_SHA + "?recursive=1")) return tree(
                entry(PATH_ONE, BLOB_ONE) + "," + entry(PATH_TWO, BLOB_TWO));
        if (value.equals(blobUrl(BLOB_TWO))) return blob(scoreJson("exynos-2400", "Exynos 2400"));
        return response(uri);
    }

    private byte[] changedTreeResponse(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/trees/" + DATA_SHA)) return bytes("""
                {"tree":[{"path":"soc","type":"tree","sha":"%s"}]}
                """.formatted(CURRENT_SHA));
        if (value.endsWith("/trees/" + BASE_SHA + "?recursive=1")) return tree(entry(PATH_ONE, BLOB_ONE));
        if (value.endsWith("/trees/" + CURRENT_SHA + "?recursive=1")) return tree(
                entry(PATH_ONE, BLOB_ONE) + "," + entry(PATH_TWO, BLOB_TWO));
        if (value.equals(blobUrl(BLOB_TWO))) return blob(scoreJson("exynos-2400", "Snapdragon 8 Elite"));
        return response(uri);
    }

    private byte[] changedSamePathResponse(URI uri) {
        String value = uri.toString();
        if (value.endsWith("/trees/" + DATA_SHA)) return bytes("""
                {"tree":[{"path":"soc","type":"tree","sha":"%s"}]}
                """.formatted(CURRENT_SHA));
        if (value.endsWith("/trees/" + BASE_SHA + "?recursive=1")) return tree(entry(PATH_ONE, BLOB_ONE));
        if (value.endsWith("/trees/" + CURRENT_SHA + "?recursive=1")) return tree(entry(PATH_ONE, BLOB_TWO));
        return response(uri);
    }

    private static String entry(String path, String sha) {
        return "{\"path\":\"%s\",\"type\":\"blob\",\"sha\":\"%s\"}".formatted(path, sha);
    }

    private static byte[] tree(String entries) {
        return bytes("{\"truncated\":false,\"tree\":[" + entries + "]}");
    }

    private static byte[] blob(String document) {
        return bytes("{\"encoding\":\"base64\",\"content\":\""
                + Base64.getEncoder().encodeToString(bytes(document)) + "\"}");
    }

    private static String scoreJson(String slug, String name) {
        return """
                {"slug":"%s","name":"%s","verified":true,
                 "geekbench_single":3200,"geekbench_multi":10000,
                 "updated_at":"2026-05-28T23:32:19"}
                """.formatted(slug, name);
    }

    private static String blobUrl(String sha) {
        return "https://api.github.com/repos/GetTechAPI/TechAPI/git/blobs/" + sha;
    }

    private static TechApiBenchmarkRepository.PhoneTarget phone(
            long id, String brand, String model, String chipset) {
        return new TechApiBenchmarkRepository.PhoneTarget(id, brand, model, chipset);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
