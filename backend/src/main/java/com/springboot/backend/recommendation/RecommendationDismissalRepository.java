package com.springboot.backend.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class RecommendationDismissalRepository {

    private final JdbcTemplate db;

    public RecommendationDismissalRepository(
            JdbcTemplate db) {

        this.db = db;
    }

    /**
     * Dismisses only an ACTIVE recommendation belonging to the
     * authenticated user and one of their current devices.
     *
     * <p>The status change and feedback insertion occur in one SQL
     * statement, so one cannot succeed without the other.
     *
     * @return false when no matching active recommendation exists
     */
    public boolean dismiss(
            long userId,
            long recommendationId,
            RecommendationDismissalReason reason,
            String comment,
            OffsetDateTime cooldownUntil) {

        List<Long> insertedIds = db.queryForList(
                """
                WITH dismissed AS (
                    UPDATE recommendations r
                    SET status = 'DISMISSED'
                    FROM user_devices ud
                    WHERE r.id = ?
                      AND r.user_id = ?
                      AND r.status = 'ACTIVE'
                      AND ud.id = r.current_device_id
                      AND ud.user_id = r.user_id
                      AND ud.is_current = TRUE
                    RETURNING
                        r.id,
                        r.user_id,
                        r.candidate_product_id
                )
                INSERT INTO recommendation_dismissals (
                    recommendation_id,
                    user_id,
                    candidate_product_id,
                    reason,
                    comment,
                    cooldown_until
                )
                SELECT
                    id,
                    user_id,
                    candidate_product_id,
                    ?,
                    ?,
                    ?
                FROM dismissed
                RETURNING id
                """,
                Long.class,
                recommendationId,
                userId,
                reason.name(),
                comment,
                cooldownUntil
        );

        return !insertedIds.isEmpty();
    }
}