package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.springboot.backend.email.RecommendationEmailSender;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.AiAssessmentClient;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.AssessResponse;
import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.recommendation.trigger.RecommendationTriggerConfiguration;
import com.springboot.backend.recommendation.trigger.RecommendationTriggerService;
import com.springboot.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCRUM-143 verdict channel, end to end with a mocked AI client (safe for CI).
 * Expectations: {@code docs/e2e/SCRUM-143-expectations.md}, scenarios 1-6.
 *
 * <p>Deliberately not re-tested here: the brand/budget filter itself (PR 88,
 * {@code CandidateFilterIntegrationTest}) and the price-drop event/dashboard mechanics (PR 87,
 * {@code SimulatedRecommendationWorkflowIntegrationTest}).
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN",
        "recommendation.triggers.ai-assessment-enabled=true"
})
@ActiveProfiles("ingestion-demo")
@DirtiesContext
class Scrum143VerdictE2EIntegrationTest {

    private static final String RESULTS = "verdict-runs.jsonl";

    @Autowired private RecommendationTriggerService triggers;
    @Autowired private SimulatedMarketChangeService changes;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate db;
    @Autowired @Qualifier(RecommendationTriggerConfiguration.EXECUTOR)
    private ThreadPoolTaskExecutor executor;

    @MockitoBean private AiAssessmentClient aiClient;
    @MockitoBean private RecommendationEmailSender emailSender;

    private final JsonMapper json = JsonMapper.builder().build();
    private Scrum143Fixture fixture;

    @BeforeEach
    void setUp() throws InterruptedException {
        fixture = new Scrum143Fixture(db, users, executor);
        fixture.assertTestDatabase();
        fixture.removeAbandonedFixture();
        reset(aiClient, emailSender);
        when(aiClient.assess(any())).thenAnswer(invocation -> mockedAi(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        fixture.removeAbandonedFixture();
    }

    @Test
    void scenario1_baseBigBatteryGainOnTheOnlyPriorityIsAStrongUpgrade() {
        Pair pair = pair(Scrum143Fixture.Device.base());
        triggers.runForDevice(pair.deviceId(), Scrum143Fixture.CAUSE);

        assertVerdict("1", pair, TierMapper.STRONG_UPGRADE_CANDIDATE, 1);
    }

    @Test
    void scenario2_topPriorityUnchangedMeansNoMeaningfulChangeAndNoAiCall() {
        Pair pair = pair(Scrum143Fixture.Device.base().withPriorities("{\"battery\":1,\"performance\":5}"));
        triggers.runForDevice(pair.deviceId(), Scrum143Fixture.CAUSE);

        assertVerdict("2", pair, TierMapper.NO_MEANINGFUL_CHANGE, 0);
    }

    @Test
    void scenario3_brandLockedOwnerGetsNoRowAndNoAiCallForAnotherBrand() {
        String unique = Scrum143Fixture.unique();
        User user = fixture.user();
        Long owned = fixture.ownedPhone(unique);
        Long candidate = fixture.candidatePhone(Scrum143Fixture.OTHER_BRAND, unique, "1000.00");
        Long device = fixture.ownedDevice(user.getId(), owned,
                Scrum143Fixture.Device.base().withFlexibility("NOT_FLEXIBLE"));

        triggers.runForDevice(device, Scrum143Fixture.CAUSE);

        int rows = fixture.anyRows(device, candidate);
        record("3", user.getId(), device, candidate, null, rows);
        assertEquals(0, rows, "brand-locked device must get no recommendation row for another brand");
        verify(aiClient, never()).assess(forCandidate(candidate));
    }

    @Test
    void scenario4_happyOwnerInExcellentConditionGetsTheSameVerdictAsScenario1() {
        Pair pair = pair(Scrum143Fixture.Device.base().withOwner("EXCELLENT", 95));
        triggers.runForDevice(pair.deviceId(), Scrum143Fixture.CAUSE);

        assertVerdict("4", pair, TierMapper.STRONG_UPGRADE_CANDIDATE, 1);
    }

    @Test
    void scenario5_priceDropIntoBudgetCreatesAStrongRecommendationWithAFullAuditTrail() throws Exception {
        String unique = Scrum143Fixture.unique();
        User user = fixture.user();
        Long owned = fixture.ownedPhone(unique);
        Long candidate = fixture.candidatePhone(Scrum143Fixture.BRAND, unique, "1300.00");
        Long device = fixture.ownedDevice(user.getId(), owned, Scrum143Fixture.Device.base());

        triggers.runForDevice(device, Scrum143Fixture.CAUSE);
        assertEquals(0, fixture.anyRows(device, candidate), "before the drop: no recommendation");
        verify(aiClient, never()).assess(forCandidate(candidate));

        changes.injectPrice(new SimulatedMarketChangeService.PriceCommand(
                candidate, new BigDecimal("1100.00"), "scrum143-price-" + unique));
        Scrum143Fixture.await(() -> fixture.activeRows(device, candidate) == 1,
                "active recommendation after the price drop", 20);
        fixture.awaitIdle();

        Pair pair = new Pair(user.getId(), owned, candidate, device);
        Map<String, Object> row = assertVerdict("5", pair, TierMapper.STRONG_UPGRADE_CANDIDATE, 1);

        JsonNode snapshot = json.readTree((String) row.get("input_snapshot"));
        for (String key : List.of("owned_device", "preferences", "candidate", "analysis")) {
            assertTrue(snapshot.has(key) && !snapshot.get(key).isEmpty(), "input_snapshot." + key + " must be filled in");
        }
        JsonNode factors = json.readTree((String) row.get("factor_analysis")).path("deterministic").path("factors");
        assertFalse(factors.isEmpty(), "factor_analysis.deterministic.factors must be filled in");
        assertTrue(factors.has("battery"), "factor breakdown must include the deciding factor");
    }

    @Test
    void scenario6_oneCandidateTwoDevicesWithDifferentPrioritiesGetDifferentVerdicts() {
        String unique = Scrum143Fixture.unique();
        User user = fixture.user();
        Long owned = fixture.ownedPhone(unique);
        Long candidate = fixture.candidatePhone(unique);
        Long deviceA = fixture.ownedDevice(user.getId(), owned, Scrum143Fixture.Device.base());
        Long deviceB = fixture.ownedDevice(user.getId(), owned,
                Scrum143Fixture.Device.base().withPriorities("{\"battery\":3,\"performance\":1}"));

        triggers.runForDevice(deviceA, Scrum143Fixture.CAUSE);
        triggers.runForDevice(deviceB, Scrum143Fixture.CAUSE);

        String a = (String) fixture.activeRow(deviceA, candidate).get("verdict");
        String b = (String) fixture.activeRow(deviceB, candidate).get("verdict");
        record("6A", user.getId(), deviceA, candidate, fixture.activeRow(deviceA, candidate), 1);
        record("6B", user.getId(), deviceB, candidate, fixture.activeRow(deviceB, candidate), 1);
        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, a, "device A (battery 5)");
        assertEquals(TierMapper.WORTH_CONSIDERING, b, "device B (battery 3, performance 1)");
        verify(aiClient, times(2)).assess(forCandidate(candidate));
    }

    private record Pair(long userId, long ownedId, long candidateId, long deviceId) {}

    private Pair pair(Scrum143Fixture.Device device) {
        String unique = Scrum143Fixture.unique();
        User user = fixture.user();
        Long owned = fixture.ownedPhone(unique);
        Long candidate = fixture.candidatePhone(unique);
        return new Pair(user.getId(), owned, candidate, fixture.ownedDevice(user.getId(), owned, device));
    }

    private Map<String, Object> assertVerdict(String scenario, Pair pair, String expected, int aiCalls) {
        assertEquals(1, fixture.activeRows(pair.deviceId(), pair.candidateId()),
                "scenario " + scenario + ": exactly one active row");
        Map<String, Object> row = fixture.activeRow(pair.deviceId(), pair.candidateId());
        record(scenario, pair.userId(), pair.deviceId(), pair.candidateId(), row, aiCalls);
        assertEquals(expected, row.get("verdict"), "scenario " + scenario + " verdict");
        verify(aiClient, times(aiCalls)).assess(forCandidate(pair.candidateId()));
        return row;
    }

    private static AssessRequest forCandidate(long candidateId) {
        return argThat(request -> request != null && request.candidate().productId() == candidateId);
    }

    private void record(String scenario, long userId, long deviceId, long candidateId,
                        Map<String, Object> row, int expectedAiCalls) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenario", scenario);
        run.put("user_id", userId);
        run.put("device_id", deviceId);
        run.put("candidate_id", candidateId);
        run.put("recommendation_id", row == null ? "none" : row.get("id"));
        run.put("verdict", row == null ? null : row.get("verdict"));
        run.put("grade", row == null ? null : row.get("confidence"));
        run.put("expected_ai_calls", expectedAiCalls);
        run.put("snapshot_non_empty", row != null && !"{}".equals(row.get("input_snapshot")));
        run.put("factor_analysis_non_empty", row != null && !"{}".equals(row.get("factor_analysis")));
        Scrum143Fixture.record(RESULTS, run);
    }

    /** Same canned success as PR 87: the verdict channel never depends on what the AI says. */
    private static AssessResponse mockedAi(AssessRequest request) {
        return new AssessResponse(
                request.requestId(), "B",
                List.of(new AssessResponse.EvidenceFinding(
                        "battery", "POSITIVE", List.of("P1"), List.of(1L), "Owners report good endurance.")),
                List.of(), List.of(), "Owners are broadly positive.",
                new AssessResponse.ResponseMeta(
                        "scrum143-mock-model", "v2", List.of(1L), 0,
                        Map.of("k", 12, "chunk_char_cap", 800, "vector_store", "local", "embedding_dim", 512),
                        false, null),
                null);
    }
}
