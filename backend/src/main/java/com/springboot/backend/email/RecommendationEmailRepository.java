package com.springboot.backend.email;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;

@Component
public class RecommendationEmailRepository {

    public static final String LOG_COMPONENT = "RECOMMENDATION_EMAIL";

    private final JdbcTemplate db;
    private final RowMapper<EmailRecommendation> recommendationMapper = (rs, row) -> new EmailRecommendation(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("candidate_product_id"),
            rs.getString("email"),
            rs.getBoolean("enabled"),
            rs.getBoolean("recommendation_emails_enabled"),
            rs.getString("product_name"),
            rs.getString("verdict"),
            rs.getString("confidence"),
            rs.getBigDecimal("price"),
            rs.getString("currency"),
            rs.getBigDecimal("upgrade_score"),
            rs.getString("factor_analysis"),
            rs.getString("reasoning")
    );

    public RecommendationEmailRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<EmailRecommendation> findEligibleAccountRecommendation(long recommendationId) {
        return db.query(
                """
                SELECT r.id,
                       r.user_id,
                       r.candidate_product_id,
                       u.email,
                       u.enabled,
                       u.recommendation_emails_enabled,
                       CONCAT_WS(' ', p.brand, p.model_name) AS product_name,
                       r.verdict,
                       r.confidence,
                       latest_price.price,
                       latest_price.currency,
                       CASE
                           WHEN jsonb_typeof(r.factor_analysis -> 'deterministic' -> 'upgrade_score') = 'number'
                           THEN (r.factor_analysis -> 'deterministic' ->> 'upgrade_score')::numeric
                       END AS upgrade_score,
                       r.factor_analysis::text AS factor_analysis,
                       r.reasoning
                FROM recommendations r
                JOIN users u ON u.id = r.user_id
                LEFT JOIN user_devices ud ON ud.id = r.current_device_id
                    AND ud.user_id = r.user_id
                JOIN products p ON p.id = r.candidate_product_id
                LEFT JOIN LATERAL (
                    SELECT ph.price, ph.currency
                    FROM price_history ph
                    WHERE ph.product_id = r.candidate_product_id
                    ORDER BY ph.observed_at DESC, ph.id DESC
                    LIMIT 1
                ) latest_price ON TRUE
                WHERE r.id = ?
                  AND r.status = 'ACTIVE'
                  AND r.email_status = 'UNCHECKED'
                  AND (r.current_device_id IS NULL OR ud.is_current = TRUE)
                """,
                recommendationMapper,
                recommendationId
        ).stream().findFirst();
    }

    public List<EmailRecommendation> findUncheckedActiveDeviceRecommendations(long userDeviceId) {
        return db.query(
                """
                SELECT r.id,
                       r.user_id,
                       r.candidate_product_id,
                       u.email,
                       u.enabled,
                       u.recommendation_emails_enabled,
                       CONCAT_WS(' ', p.brand, p.model_name) AS product_name,
                       r.verdict,
                       r.confidence,
                       latest_price.price,
                       latest_price.currency,
                       CASE
                           WHEN jsonb_typeof(r.factor_analysis -> 'deterministic' -> 'upgrade_score') = 'number'
                           THEN (r.factor_analysis -> 'deterministic' ->> 'upgrade_score')::numeric
                       END AS upgrade_score,
                       r.factor_analysis::text AS factor_analysis,
                       r.reasoning
                FROM recommendations r
                JOIN users u ON u.id = r.user_id
                JOIN user_devices ud ON ud.id = r.current_device_id
                    AND ud.user_id = r.user_id
                    AND ud.is_current = TRUE
                JOIN products p ON p.id = r.candidate_product_id
                LEFT JOIN LATERAL (
                    SELECT ph.price, ph.currency
                    FROM price_history ph
                    WHERE ph.product_id = r.candidate_product_id
                    ORDER BY ph.observed_at DESC, ph.id DESC
                    LIMIT 1
                ) latest_price ON TRUE
                WHERE r.current_device_id = ?
                  AND r.status = 'ACTIVE'
                  AND r.email_status = 'UNCHECKED'
                """,
                recommendationMapper,
                userDeviceId
        );
    }

    public boolean isRecommendationSendable(long recommendationId) {
        return Boolean.TRUE.equals(db.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM recommendations r
                    JOIN users u ON u.id = r.user_id
                    LEFT JOIN user_devices ud ON ud.id = r.current_device_id
                        AND ud.user_id = r.user_id
                    WHERE r.id = ?
                      AND r.status = 'ACTIVE'
                      AND r.email_status = 'SENDING'
                      AND (r.current_device_id IS NULL OR ud.is_current = TRUE)
                      AND u.enabled = TRUE
                      AND u.recommendation_emails_enabled = TRUE
                )
                """,
                Boolean.class,
                recommendationId
        ));
    }

    public boolean areDeviceSummaryRecommendationsSendable(
            long userDeviceId,
            long userId,
            List<Long> recommendationIds) {
        if (recommendationIds.isEmpty()) {
            return false;
        }

        String placeholders = String.join(",", Collections.nCopies(recommendationIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(userDeviceId);
        arguments.add(userId);
        arguments.addAll(recommendationIds);
        Long count = db.queryForObject(
                "SELECT COUNT(*) FROM recommendations r "
                        + "JOIN user_devices ud ON ud.id = r.current_device_id AND ud.user_id = r.user_id "
                        + "JOIN users u ON u.id = r.user_id "
                        + "WHERE r.current_device_id = ? AND r.user_id = ? "
                        + "AND r.id IN (" + placeholders + ") "
                        + "AND r.status = 'ACTIVE' AND r.email_status = 'SENDING' "
                        + "AND ud.is_current = TRUE AND u.enabled = TRUE "
                        + "AND u.recommendation_emails_enabled = TRUE",
                Long.class,
                arguments.toArray());
        return count != null && count == recommendationIds.size();
    }

    public boolean markPending(long recommendationId) {
        return db.update(
            "UPDATE recommendations SET email_status = 'PENDING' WHERE id = ? AND email_status = 'UNCHECKED'",
            recommendationId
        ) > 0;
    }

    public void markNotEligible(long recommendationId) {
        db.update(
                "UPDATE recommendations SET email_status = 'NOT_ELIGIBLE' WHERE id = ? AND email_status = 'UNCHECKED'",
                recommendationId
        );
    }

    public void markDeviceRecommendationsNotSelected(long userDeviceId, List<Long> selectedIds) {
        if (selectedIds.isEmpty()) {
            db.update(
                    "UPDATE recommendations SET email_status = 'NOT_ELIGIBLE' "
                            + "WHERE current_device_id = ? AND status = 'ACTIVE' AND email_status = 'UNCHECKED'",
                    userDeviceId);
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(selectedIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(userDeviceId);
        arguments.addAll(selectedIds);
        db.update(
                "UPDATE recommendations SET email_status = 'NOT_ELIGIBLE' "
                        + "WHERE current_device_id = ? AND status = 'ACTIVE' AND email_status = 'UNCHECKED' "
                        + "AND id NOT IN (" + placeholders + ")",
                arguments.toArray());
    }

    public void abandon(List<Long> recommendationIds) {
        recommendationIds.forEach(id -> db.update(
                "UPDATE recommendations SET email_status = 'NOT_ELIGIBLE' "
                        + "WHERE id = ? AND email_status IN ('PENDING', 'SENDING')",
                id));
    }

    public boolean claim(long recommendationId) {
        return db.update(
                "UPDATE recommendations SET email_status = 'SENDING' WHERE id = ? AND email_status = 'PENDING'",
                recommendationId
        ) > 0;
    }

    @Transactional
    public void recordSuccess(long recommendationId, long userId) {
        db.update(
                """
                UPDATE recommendations
                SET email_status = 'SENT',
                    email_attempts = email_attempts + 1,
                    email_sent_at = CURRENT_TIMESTAMP,
                    email_last_attempted_at = CURRENT_TIMESTAMP,
                    email_last_error = NULL
                WHERE id = ?
                """,
                recommendationId
        );

        logAttempt(recommendationId, userId, "SUCCESS", "Recommendation email sent");
    }

    @Transactional
    public void recordFailure(long recommendationId, long userId, String error, boolean finalFailure) {
        String status = finalFailure ? "FAILED" : "SENDING";

        db.update(
                """
                UPDATE recommendations
                SET email_status = ?,
                    email_attempts = email_attempts + 1,
                    email_last_attempted_at = CURRENT_TIMESTAMP,
                    email_last_error = ?
                WHERE id = ?
                """,
                status,
                error,
                recommendationId
        );

        logAttempt(
                recommendationId,
                userId,
                finalFailure ? "FAILURE" : "RETRY",
                finalFailure ? "Recommendation email failed" : "Recommendation email will be retried"
        );
    }

    @Transactional
    public void recordSummarySuccess(List<Long> recommendationIds, long userId) {
        recommendationIds.forEach(id -> recordSuccess(id, userId));
    }

    @Transactional
    public void recordSummaryFailure(
            List<Long> recommendationIds,
            long userId,
            String error,
            boolean finalFailure) {
        recommendationIds.forEach(id -> recordFailure(id, userId, error, finalFailure));
    }

    private void logAttempt(long recommendationId, long userId, String status, String message) {
        db.update(
                """
                INSERT INTO system_log (component, status, message, metadata)
                VALUES (?, ?, ?, jsonb_build_object(
                    'userId', ?,
                    'recommendationId', ?
                ))
                """,
                LOG_COMPONENT,
                status,
                message,
                userId,
                recommendationId
        );
    }
}
