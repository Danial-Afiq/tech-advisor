-- Each owned device needs its own active recommendation for a candidate.
-- The old index keyed only on user and candidate, so evaluating a second owned
-- device superseded the first device's dashboard recommendations.
DROP INDEX recommendations_active_unique;

CREATE UNIQUE INDEX recommendations_active_unique
    ON recommendations (user_id, current_device_id, candidate_product_id)
    NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';
