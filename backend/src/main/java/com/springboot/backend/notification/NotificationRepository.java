package com.springboot.backend.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code notifications} persistence. JdbcTemplate, following
 * {@link com.springboot.backend.recommendation.RecommendationRepository}.
 * Every read and update is scoped by {@code user_id}, so another user's
 * notification behaves exactly like one that does not exist.
 */
@Component
public class NotificationRepository {
    private final JdbcTemplate db;

    public NotificationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public long insert(long userId, String type, String title, String message, Long recommendationId) {
        return db.queryForObject(
                "INSERT INTO notifications (user_id, type, title, message, recommendation_id) "
                        + "VALUES (?,?,?,?,?) RETURNING id",
                Long.class, userId, type, title, message, recommendationId);
    }

    /**
     * Newest first. {@code available} is true only while the linked
     * recommendation is exactly what the dashboard shows: ACTIVE and on a
     * current device of this user.
     */
    public List<NotificationResponse> findPage(long userId, int offset, int limit) {
        return db.query(
                """
                SELECT n.id, n.type, n.title, n.message, n.recommendation_id, n.created_at,
                       n.read_at IS NOT NULL AS is_read,
                       (r.id IS NOT NULL AND ud.id IS NOT NULL) AS available
                FROM notifications n
                LEFT JOIN recommendations r
                    ON r.id = n.recommendation_id AND r.status = 'ACTIVE'
                LEFT JOIN user_devices ud
                    ON ud.id = r.current_device_id AND ud.user_id = n.user_id AND ud.is_current = TRUE
                WHERE n.user_id = ?
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new NotificationResponse(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("message"),
                        rs.getObject("recommendation_id", Long.class),
                        rs.getBoolean("available"),
                        rs.getBoolean("is_read"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId, limit, offset);
    }

    public long count(long userId) {
        return db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=?", Long.class, userId);
    }

    public long countUnread(long userId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Long.class, userId);
    }

    /** @return false when no such notification belongs to this user. Re-reading keeps the first time. */
    public boolean markRead(long userId, long notificationId) {
        return db.update(
                "UPDATE notifications SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP) WHERE id=? AND user_id=?",
                notificationId, userId) > 0;
    }

    public int markAllRead(long userId) {
        return db.update(
                "UPDATE notifications SET read_at = CURRENT_TIMESTAMP WHERE user_id=? AND read_at IS NULL",
                userId);
    }

    /** Custom name, else catalogue brand + model, else empty. */
    public Optional<String> deviceName(long userDeviceId) {
        return db.query(
                """
                SELECT COALESCE(NULLIF(BTRIM(ud.custom_name), ''),
                                NULLIF(CONCAT_WS(' ', p.brand, p.model_name), '')) AS name
                FROM user_devices ud LEFT JOIN products p ON p.id = ud.product_id
                WHERE ud.id = ?
                """,
                (rs, row) -> rs.getString("name"), userDeviceId)
                .stream().filter(name -> name != null).findFirst();
    }

    public Optional<String> productName(long productId) {
        return db.query(
                "SELECT NULLIF(CONCAT_WS(' ', brand, model_name), '') AS name FROM products WHERE id = ?",
                (rs, row) -> rs.getString("name"), productId)
                .stream().filter(name -> name != null).findFirst();
    }

    public void logFailure(String message, String metadataJson) {
        db.update(
                "INSERT INTO system_log (component, status, message, metadata) VALUES (?,?,?,?::jsonb)",
                NotificationService.LOG_COMPONENT, "FAILURE", message, metadataJson);
    }
}
