package com.springboot.backend.recommendation;

import com.springboot.backend.dto.DashboardRecommendationResponse.SpecChange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardSpecChangesTest {

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void correctsDirectionWithTheVerdictsRulesBiggestChangeFirst() {
        // Serialised the way input_snapshot.computed.spec_deltas is persisted.
        Map<String, AssessRequest.SpecDelta> deltas = new LinkedHashMap<>();
        deltas.put("battery_mah", new AssessRequest.SpecDelta(4352.0, 4610.0, 5.9));
        // Raw -19.6%: the candidate is lighter, which is better.
        deltas.put("weight_g", new AssessRequest.SpecDelta(240.0, 193.0, -19.6));
        deltas.put("software_support_years", new AssessRequest.SpecDelta(6.0, 4.0, -33.3));
        deltas.put("benchmark_score", new AssessRequest.SpecDelta(1000.0, 1300.0, 30.0));
        // Not specs the card should summarise: budget headroom, text, unknown.
        deltas.put("price", new AssessRequest.SpecDelta(2000.0, 1099.0, 45.1));
        deltas.put("chipset", new AssessRequest.SpecDelta("A15", "Snapdragon 8 Gen 3", null));
        deltas.put("ram_gb", new AssessRequest.SpecDelta(6.0, null, null));

        assertEquals(
                List.of(new SpecChange("software_support_years", -33.3),
                        new SpecChange("benchmark_score", 30.0),
                        new SpecChange("weight_g", 19.6),
                        new SpecChange("battery_mah", 5.9)),
                RecommendationRepository.specChanges(json.writeValueAsString(deltas)));
    }

    @Test
    void readsARowAsStoredInTheDatabase() {
        String stored = """
                {"os": {"current": "iOS", "candidate": "Android", "delta_pct": null},
                 "price": {"current": 2000.0, "candidate": 1099.0, "delta_pct": 45.1},
                 "ram_gb": {"current": 6.0, "candidate": 12.0, "delta_pct": 100.0},
                 "storage_gb": {"current": 128.0, "candidate": 512.0, "delta_pct": 300.0}}""";

        assertEquals(
                List.of(new SpecChange("storage_gb", 300.0), new SpecChange("ram_gb", 100.0)),
                RecommendationRepository.specChanges(stored));
    }

    @Test
    void olderOrMalformedShapesYieldNoChanges() {
        assertEquals(List.of(), RecommendationRepository.specChanges(null));
        assertEquals(List.of(), RecommendationRepository.specChanges("{\"ram_gb\":{\"delta_pct\":\"big\"}}"));
        assertEquals(List.of(), RecommendationRepository.specChanges("[1, \"x\"]"));
        assertEquals(List.of(), RecommendationRepository.specChanges("not json"));
    }
}
