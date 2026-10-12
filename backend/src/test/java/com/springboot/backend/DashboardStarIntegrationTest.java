package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationRecord;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket 4.4: starring (saving) a dashboard recommendation. Same context
 * properties as {@link DashboardIntegrationTest}, so the Spring context is shared.
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN"
})
class DashboardStarIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate db;

    @Autowired
    RecommendationRepository recommendations;

    MockMvc mvc;
    User owner;
    User otherUser;
    long candidateProductId;
    long currentDeviceId;
    long activeRecommendationId;
    String ownerToken;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String unique = UUID.randomUUID().toString();
        owner = userRepository.save(new User(
                "star-owner-" + unique + "@example.com", "unused-hash", "USER"));
        otherUser = userRepository.save(new User(
                "star-other-" + unique + "@example.com", "unused-hash", "USER"));
        ownerToken = jwtService.generateToken(owner);

        candidateProductId = db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES (?, ?) RETURNING id",
                Long.class, "StarBrand-" + unique, "Star Model");

        currentDeviceId = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, ?, TRUE) RETURNING id",
                Long.class, owner.getId(), "Daily phone");

        activeRecommendationId = insertRecommendation(owner.getId(), currentDeviceId, "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        // Users cascade to their devices and recommendations.
        userRepository.deleteById(owner.getId());
        userRepository.deleteById(otherUser.getId());
        db.update("DELETE FROM products WHERE id = ?", candidateProductId);
    }

    @Test
    void starringSavesTheRecommendationAndUnstarringRemovesIt() throws Exception {
        mvc.perform(get("/api/dashboard/recommendations").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].starred").value(false));

        mvc.perform(put(starPath(activeRecommendationId)).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        assertThat(starredInDatabase(activeRecommendationId)).isTrue();
        mvc.perform(get("/api/dashboard/recommendations").header("Authorization", bearer(ownerToken)))
                .andExpect(jsonPath("$[0].recommendationId").value(activeRecommendationId))
                .andExpect(jsonPath("$[0].starred").value(true));

        // Starring twice is idempotent.
        mvc.perform(put(starPath(activeRecommendationId)).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        mvc.perform(delete(starPath(activeRecommendationId)).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        assertThat(starredInDatabase(activeRecommendationId)).isFalse();
        mvc.perform(get("/api/dashboard/recommendations").header("Authorization", bearer(ownerToken)))
                .andExpect(jsonPath("$[0].starred").value(false));
    }

    @Test
    void starRequiresAuthentication() throws Exception {
        mvc.perform(put(starPath(activeRecommendationId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(starPath(activeRecommendationId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersRecommendationIsNotFound() throws Exception {
        String otherToken = jwtService.generateToken(otherUser);

        mvc.perform(put(starPath(activeRecommendationId)).header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());

        assertThat(starredInDatabase(activeRecommendationId)).isFalse();
    }

    @Test
    void recommendationsTheDashboardDoesNotShowCannotBeStarred() throws Exception {
        long superseded = insertRecommendation(owner.getId(), currentDeviceId, "SUPERSEDED");
        long removedDevice = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, ?, FALSE) RETURNING id",
                Long.class, owner.getId(), "Old phone");
        long otherCandidate = db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES (?, ?) RETURNING id",
                Long.class, "StarBrand-" + UUID.randomUUID(), "Other Model");
        try {
            // ACTIVE, but for a device the user no longer has as current.
            long onRemovedDevice = db.queryForObject(
                    """
                    INSERT INTO recommendations (user_id, current_device_id, candidate_product_id, verdict)
                    VALUES (?, ?, ?, 'WORTH_CONSIDERING')
                    RETURNING id
                    """,
                    Long.class, owner.getId(), removedDevice, otherCandidate);

            for (long id : new long[] {superseded, onRemovedDevice, Long.MAX_VALUE}) {
                mvc.perform(put(starPath(id)).header("Authorization", bearer(ownerToken)))
                        .andExpect(status().isNotFound());
            }
        } finally {
            db.update("DELETE FROM products WHERE id = ?", otherCandidate);
        }
    }

    @Test
    void reassessmentKeepsTheStarOnTheReplacementRow() throws Exception {
        mvc.perform(put(starPath(activeRecommendationId)).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        long replacement = recommendations.save(new RecommendationRecord(
                owner.getId(), currentDeviceId, candidateProductId, null,
                "STRONG_UPGRADE_CANDIDATE", null, Map.of(), Map.of(),
                "Re-assessed", null, null), null);

        assertThat(replacement).isNotEqualTo(activeRecommendationId);
        assertThat(starredInDatabase(replacement)).isTrue();
        mvc.perform(get("/api/dashboard/recommendations").header("Authorization", bearer(ownerToken)))
                .andExpect(jsonPath("$[0].recommendationId").value(replacement))
                .andExpect(jsonPath("$[0].starred").value(true));
    }

    @Test
    void anUnstarredRecommendationStaysUnstarredAfterReassessment() {
        long replacement = recommendations.save(new RecommendationRecord(
                owner.getId(), currentDeviceId, candidateProductId, null,
                "WORTH_WATCHING", null, Map.of(), Map.of(),
                "Re-assessed", null, null), null);

        assertThat(starredInDatabase(replacement)).isFalse();
    }

    private long insertRecommendation(long userId, long deviceId, String status) {
        return db.queryForObject(
                """
                INSERT INTO recommendations (user_id, current_device_id, candidate_product_id, verdict, status)
                VALUES (?, ?, ?, 'WORTH_CONSIDERING', ?)
                RETURNING id
                """,
                Long.class, userId, deviceId, candidateProductId, status);
    }

    private boolean starredInDatabase(long recommendationId) {
        return db.queryForObject(
                "SELECT starred FROM recommendations WHERE id = ?", Boolean.class, recommendationId);
    }

    private static String starPath(long recommendationId) {
        return "/api/dashboard/recommendations/" + recommendationId + "/star";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
