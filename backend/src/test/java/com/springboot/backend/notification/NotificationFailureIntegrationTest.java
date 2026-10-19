package com.springboot.backend.notification;

import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.RecommendationRecord;
import com.springboot.backend.recommendation.RecommendationRepository;
import com.springboot.backend.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * A failed notification must never cost the recommendation (ticket 4.4.2):
 * the recommendation stays saved and the failure is written to system_log.
 * Its own context because of the spy, released afterwards (AGENTS.md §20.1).
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=ERROR"
})
@DirtiesContext
class NotificationFailureIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate db;
    @Autowired RecommendationRepository recommendations;
    @MockitoSpyBean NotificationRepository notificationRepository;

    @Test
    void aFailedNotificationKeepsTheRecommendationAndIsLogged() {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(new User("notify-fail-" + unique + "@example.com", "unused-hash", "USER"));
        long product = db.queryForObject(
                "INSERT INTO products (brand, model_name) VALUES (?, 'Model') RETURNING id",
                Long.class, "FailBrand-" + unique);
        long device = db.queryForObject(
                "INSERT INTO user_devices (user_id, custom_name, is_current) VALUES (?, 'Phone', TRUE) RETURNING id",
                Long.class, owner.getId());
        doThrow(new DataAccessResourceFailureException("simulated outage"))
                .when(notificationRepository).insert(anyLong(), any(), any(), any(), any());

        try {
            RecommendationRecord rec = new RecommendationRecord(owner.getId(), device, product, null,
                    "STRONG_UPGRADE_CANDIDATE", null, Map.of(), Map.of(), "reasoning", null, null);

            recommendations.replaceForDevice(device, List.of(product), List.of(rec));

            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM recommendations WHERE user_id=? AND status='ACTIVE'",
                    Long.class, owner.getId())).isEqualTo(1);
            assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id=?", Long.class, owner.getId())).isZero();

            Map<String, Object> log = db.queryForMap(
                    "SELECT status, message, metadata::text AS metadata FROM system_log "
                            + "WHERE component='NOTIFICATIONS' AND metadata->>'user_id' = ? ORDER BY id DESC LIMIT 1",
                    String.valueOf(owner.getId()));
            assertThat(log.get("status")).isEqualTo("FAILURE");
            assertThat(log.get("message")).isEqualTo("Notification creation failed");
            assertThat((String) log.get("metadata")).contains("simulated outage").contains("recommendation_ids");
        } finally {
            db.update("DELETE FROM system_log WHERE component='NOTIFICATIONS' AND metadata->>'user_id' = ?",
                    String.valueOf(owner.getId()));
            userRepository.deleteById(owner.getId());
            db.update("DELETE FROM products WHERE id = ?", product);
        }
    }
}
