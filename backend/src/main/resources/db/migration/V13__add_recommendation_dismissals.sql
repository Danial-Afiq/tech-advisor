CREATE TABLE recommendation_dismissals (
    id                   BIGSERIAL   PRIMARY KEY,
    recommendation_id    BIGINT
                         REFERENCES recommendations (id)
                         ON DELETE SET NULL,
    user_id              BIGINT      NOT NULL
                         REFERENCES users (id)
                         ON DELETE CASCADE,
    candidate_product_id BIGINT      NOT NULL
                         REFERENCES products (id)
                         ON DELETE CASCADE,
    reason               TEXT        NOT NULL,
    comment              TEXT,
    dismissed_at         TIMESTAMPTZ NOT NULL
                         DEFAULT CURRENT_TIMESTAMP,
    cooldown_until       TIMESTAMPTZ NOT NULL,
    CONSTRAINT recommendation_dismissals_reason_check
        CHECK (
            reason IN (
                'TOO_EXPENSIVE',
                'MISSING_REQUIRED_FEATURE',
                'NOT_ENOUGH_IMPROVEMENT',
                'NOT_INTERESTED',
                'OTHER'
            )
        ),
    CONSTRAINT recommendation_dismissals_comment_length_check
        CHECK (
            comment IS NULL
            OR CHAR_LENGTH(comment) <= 1000
        )
);

CREATE UNIQUE INDEX recommendation_dismissals_recommendation_unique
    ON recommendation_dismissals (recommendation_id)
    WHERE recommendation_id IS NOT NULL;

CREATE INDEX recommendation_dismissals_cooldown_lookup
    ON recommendation_dismissals (
        user_id,
        candidate_product_id,
        cooldown_until DESC
    );