package com.springboot.backend.email;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class RecommendationEmailRepository {

    public static final String LOG_COMPONENT = "RECOMMENDATION_EMAIL";

    private final JdbcTemplate db;

    public RecommendationEmailRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<EmailRecommendation> findEligibleAccountRecommendation(long recommendationId) {
        return db.query(
                """
                SELECT r.id,
                       r.user_id,
                       u.email,
                       u.enabled,
                       u.recommendation_emails_enabled,
                       CONCAT_WS(' ', p.brand, p.model_name) AS product_name,
                       r.verdict,
                       r.confidence,
                       latest_price.price,
                       latest_price.currency,
                       r.factor_analysis::text AS factor_analysis,
                       r.reasoning
                FROM recommendations r
                JOIN users u ON u.id = r.user_id
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
                """,
                (rs, row) -> new EmailRecommendation(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("recommendation_emails_enabled"),
                        rs.getString("product_name"),
                        rs.getString("verdict"),
                        rs.getString("confidence"),
                        rs.getBigDecimal("price"),
                        rs.getString("currency"),
                        rs.getString("factor_analysis"),
                        rs.getString("reasoning")
                ),
                recommendationId
        ).stream().findFirst();
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