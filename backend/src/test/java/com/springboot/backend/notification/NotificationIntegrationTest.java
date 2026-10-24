package com.springboot.backend.notification;

import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationRecord;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket 4.4.2 against real PostgreSQL. Not {@code @Transactional}: creation
 * runs AFTER_COMMIT, which never fires inside a rolled-back test transaction.
 * Same context properties as the dashboard tests, so the context is shared.
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN"
})
class NotificationIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate db;
    @Autowired RecommendationRepository recommendations;

    MockMvc mvc;
    User owner;
    User otherUser;
    long deviceId;
    long productA;
    long productB;
    long productC;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String unique = UUID.randomUUID().toString();
        owner = userRepository.save(new User("notify-owner-" + unique + "@example.com", "unused-hash", "USER"));
        otherUser = userRepository.save(new User("notify-other-" + unique + "@example.com", "unused-hash", "USER"));

        productA = product("BrandA-" + unique, "Alpha");
        productB = product("BrandB-" + unique, "Bravo");
        productC = product("BrandC-" + unique, "Charlie");
        deviceId = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Daily phone', TRUE) RETURNING id",
                Long.class, owner.getId());
    }

    @AfterEach
    void cleanUp() {
        // Users cascade to devices, recommendations and notifications.
        userRepository.deleteById(owner.getId());
        userRepository.deleteById(otherUser.getId());
        db.update("DELETE FROM products WHERE id IN (?,?,?)", productA, productB, productC);
    }

    @Test
    void oneEvaluationCreatesOneGroupedNotificationLinkingTheBestRecommendation() {
        evaluate(rec(productA, "WORTH_WATCHING"), rec(productB, "STRONG_UPGRADE_CANDIDATE"),
                rec(productC, "NO_MEANINGFUL_CHANGE"));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("type")).isEqualTo("NEW_RECOMMENDATION");
        assertThat(rows.getFirst().get("title")).isEqualTo("New upgrade recommendations!");
        assertThat(rows.getFirst().get("message")).isEqualTo(
                "We found 2 new or updated upgrade options for your Daily phone. See what's worth upgrading to.");
        assertThat(rows.getFirst().get("recommendation_id")).isEqualTo(activeId(productB));
        assertThat(rows.getFirst().get("read_at")).isNull();
    }

    @Test
    void anUnchangedRerunCreatesNoDuplicateAndTheLinkFollowsTheNewRow() {
        evaluate(rec(productA, "STRONG_UPGRADE_CANDIDATE"));
        long firstRow = activeId(productA);

        evaluate(rec(productA, "STRONG_UPGRADE_CANDIDATE"));

        assertThat(notifications()).hasSize(1);
        assertThat(activeId(productA)).isNotEqualTo(firstRow);
        assertThat(notifications().getFirst().get("recommendation_id")).isEqualTo(activeId(productA));
    }

    @Test
    void theAiStepResavingTheSameVerdictCreatesNothing() {
        evaluate(rec(productA, "WORTH_CONSIDERING"));

        recommendations.save(rec(productA, "WORTH_CONSIDERING"), null);

        assertThat(notifications()).hasSize(1);
    }

    @Test
    void aChangedVerdictCreatesASingleNotification() {
        evaluate(rec(productA, "WORTH_WATCHING"));

        evaluate(rec(productA, "WORTH_CONSIDERING"));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().get("title")).isEqualTo("New upgrade recommendation!");
        assertThat((String) rows.getFirst().get("message"))
                .endsWith("Alpha is worth considering for your Daily phone.");
    }

    @Test
    void anotherDeviceKeepsBothRecommendationsAndNotificationLinksAvailable() throws Exception {
        evaluate(rec(productA, "WORTH_WATCHING"));
        long firstDevicesRow = activeId(productA, deviceId);
        long secondDevice = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Work phone', TRUE) RETURNING id",
                Long.class, owner.getId());

        RecommendationRecord onSecond = new RecommendationRecord(owner.getId(), secondDevice, productA, null,
                "WORTH_WATCHING", null, Map.of(), Map.of(), "reasoning", null, null);
        recommendations.replaceForDevice(secondDevice, List.of(productA), List.of(onSecond));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat((String) rows.getFirst().get("message")).endsWith("for your Work phone.");
        assertThat(rows.getFirst().get("recommendation_id")).isEqualTo(activeId(productA, secondDevice));
        assertThat(rows.get(1).get("recommendation_id")).isEqualTo(firstDevicesRow);

        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[1].available").value(true));
    }

    @Test
    void unchangedRerunsForTwoDevicesDoNotRenotify() throws Exception {
        long secondDevice = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Work phone', TRUE) RETURNING id",
                Long.class, owner.getId());
        RecommendationRecord onSecond = new RecommendationRecord(owner.getId(), secondDevice, productA, null,
                "WORTH_WATCHING", null, Map.of(), Map.of(), "reasoning", null, null);

        evaluate(rec(productA, "WORTH_WATCHING"));
        recommendations.replaceForDevice(secondDevice, List.of(productA), List.of(onSecond));
        assertThat(notifications()).hasSize(2);

        // Each device updates only its own row; unchanged verdicts do not notify again.
        evaluate(rec(productA, "WORTH_WATCHING"));
        recommendations.replaceForDevice(secondDevice, List.of(productA), List.of(onSecond));
        evaluate(rec(productA, "WORTH_WATCHING"));

        assertThat(notifications()).hasSize(2);
        long firstDevicesNotification = (Long) notifications().get(1).get("id");
        assertThat(db.queryForObject("SELECT recommendation_id FROM notifications WHERE id=?",
                Long.class, firstDevicesNotification)).isEqualTo(activeId(productA, deviceId));
    }

    @Test
    void aMarketEventEvaluationOfOneCandidateNotifiesOnce() {
        evaluate(rec(productA, "WORTH_WATCHING"));

        // The market-event trigger re-evaluates a single (device, candidate) pair.
        recommendations.replaceForCandidate(deviceId, productB, true, List.of(rec(productB, "WORTH_CONSIDERING")));
        recommendations.replaceForCandidate(deviceId, productB, true, List.of(rec(productB, "WORTH_CONSIDERING")));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat((String) rows.getFirst().get("message")).endsWith("Bravo is worth considering for your Daily phone.");
        assertThat(rows.getFirst().get("recommendation_id")).isEqualTo(activeId(productB));
    }

    @Test
    void onlyTheNewestTenAreKept() throws Exception {
        // Each verdict change creates one notification: 12 in total.
        for (int i = 0; i < 12; i++) {
            evaluate(rec(productA, i % 2 == 0 ? "WORTH_WATCHING" : "WORTH_CONSIDERING"));
        }

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(10);
        assertThat((String) rows.getFirst().get("message")).contains("worth considering");
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(jsonPath("$.totalItems").value(10))
                .andExpect(jsonPath("$.totalPages").value(1));
        mvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(jsonPath("$.count").value(10));
    }

    @Test
    void trimmingOneUserLeavesOtherUsersAlone() {
        long othersDevice = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Other phone', TRUE) RETURNING id",
                Long.class, otherUser.getId());
        recommendations.replaceForDevice(othersDevice, List.of(productB), List.of(new RecommendationRecord(
                otherUser.getId(), othersDevice, productB, null, "WORTH_WATCHING", null,
                Map.of(), Map.of(), "reasoning", null, null)));

        for (int i = 0; i < 12; i++) {
            evaluate(rec(productA, i % 2 == 0 ? "WORTH_WATCHING" : "WORTH_CONSIDERING"));
        }

        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=?", Long.class, otherUser.getId())).isEqualTo(1);
    }

    @Test
    void deletingOneRemovesItFromTheDatabaseButNotTheRecommendation() throws Exception {
        evaluate(rec(productA, "WORTH_WATCHING"));
        evaluate(rec(productA, "WORTH_CONSIDERING"));
        long newest = (Long) notifications().getFirst().get("id");
        long recommendation = activeId(productA);
        String token = jwtService.generateToken(owner);

        mvc.perform(delete("/api/notifications/" + newest).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=?", Long.class, newest)).isZero();
        assertThat(notifications()).hasSize(1);
        assertThat(activeId(productA)).isEqualTo(recommendation);
        mvc.perform(delete("/api/notifications/" + newest).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearingAllRemovesOnlyTheCallersNotificationsFromTheDatabase() throws Exception {
        evaluate(rec(productA, "WORTH_WATCHING"));
        evaluate(rec(productA, "WORTH_CONSIDERING"));
        long othersDevice = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Other phone', TRUE) RETURNING id",
                Long.class, otherUser.getId());
        recommendations.replaceForDevice(othersDevice, List.of(productB), List.of(new RecommendationRecord(
                otherUser.getId(), othersDevice, productB, null, "WORTH_WATCHING", null,
                Map.of(), Map.of(), "reasoning", null, null)));

        mvc.perform(delete("/api/notifications").header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(status().isNoContent());

        assertThat(notifications()).isEmpty();
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=?", Long.class, otherUser.getId())).isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM recommendations WHERE user_id=? AND status='ACTIVE'",
                Long.class, owner.getId())).isEqualTo(1);
    }

    @Test
    void anotherUserCannotDeleteYourNotification() throws Exception {
        evaluate(rec(productA, "WORTH_WATCHING"));
        long ownersNotification = (Long) notifications().getFirst().get("id");

        mvc.perform(delete("/api/notifications/" + ownersNotification)
                        .header("Authorization", "Bearer " + jwtService.generateToken(otherUser)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/notifications/" + ownersNotification)).andExpect(status().isUnauthorized());

        assertThat(notifications()).hasSize(1);
    }

    @Test
    void noMeaningfulChangeAloneCreatesNothing() {
        evaluate(rec(productA, "NO_MEANINGFUL_CHANGE"));

        assertThat(notifications()).isEmpty();
    }

    @Test
    void listsUnreadCountsAndMarksRead() throws Exception {
        evaluate(rec(productA, "STRONG_UPGRADE_CANDIDATE"));
        evaluate(rec(productA, "WORTH_WATCHING"));
        String token = jwtService.generateToken(owner);
        long newest = (Long) notifications().getFirst().get("id");

        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].notificationId").value(newest))
                .andExpect(jsonPath("$.items[0].recommendationId").value(activeId(productA)))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].read").value(false));

        mvc.perform(get("/api/notifications?page=1&size=1").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(2));

        mvc.perform(put("/api/notifications/" + newest + "/read").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/notifications/" + newest + "/read").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));

        mvc.perform(put("/api/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[1].read").value(true));
    }

    @Test
    void anotherUsersNotificationIsNotFoundAndNotListed() throws Exception {
        evaluate(rec(productA, "STRONG_UPGRADE_CANDIDATE"));
        long ownersNotification = (Long) notifications().getFirst().get("id");
        String otherToken = jwtService.generateToken(otherUser);

        mvc.perform(put("/api/notifications/" + ownersNotification + "/read")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(put("/api/notifications/read-all").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNoContent());

        assertThat(notifications().getFirst().get("read_at")).isNull();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notifications/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeletedRecommendationLeavesTheNotificationUnavailable() throws Exception {
        evaluate(rec(productA, "STRONG_UPGRADE_CANDIDATE"));

        // Dropped off the shortlist: its rows are deleted (AGENTS.md §18.11).
        recommendations.replaceForDevice(deviceId, List.of(), List.of());

        assertThat(notifications().getFirst().get("recommendation_id")).isNull();
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + jwtService.generateToken(owner)))
                .andExpect(jsonPath("$.items[0].recommendationId").doesNotExist())
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    private void evaluate(RecommendationRecord... records) {
        List<RecommendationRecord> list = List.of(records);
        recommendations.replaceForDevice(
                deviceId, list.stream().map(RecommendationRecord::candidateProductId).toList(), list);
    }

    private RecommendationRecord rec(long productId, String verdict) {
        return new RecommendationRecord(owner.getId(), deviceId, productId, null, verdict, null,
                Map.of(), Map.of(), "reasoning", null, null);
    }

    private long product(String brand, String model) {
        return db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES (?, ?) RETURNING id", Long.class, brand, model);
    }

    private long activeId(long productId) {
        return db.queryForObject(
                "SELECT id FROM recommendations WHERE user_id=? AND candidate_product_id=? AND status='ACTIVE'",
                Long.class, owner.getId(), productId);
    }

    private long activeId(long productId, long currentDeviceId) {
        return db.queryForObject(
                "SELECT id FROM recommendations WHERE user_id=? AND current_device_id=? "
                        + "AND candidate_product_id=? AND status='ACTIVE'",
                Long.class, owner.getId(), currentDeviceId, productId);
    }

    private List<Map<String, Object>> notifications() {
        return db.queryForList(
                "SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC, id DESC", owner.getId());
    }
}
