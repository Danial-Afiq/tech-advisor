ALTER TABLE users
    ADD COLUMN recommendation_emails_enabled BOOLEAN
        NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN users.recommendation_emails_enabled IS
    'Whether this user receives emails for eligible upgrade recommendations.';

ALTER TABLE recommendations
    ADD COLUMN email_status TEXT
        NOT NULL DEFAULT 'UNCHECKED',
    ADD COLUMN email_attempts INTEGER
        NOT NULL DEFAULT 0,
    ADD COLUMN email_sent_at TIMESTAMPTZ,
    ADD COLUMN email_last_attempted_at TIMESTAMPTZ,
    ADD COLUMN email_last_error TEXT,
    ADD CONSTRAINT recommendations_email_status_check
        CHECK (
            email_status IN (
                'UNCHECKED',
                'NOT_ELIGIBLE',
                'PENDING',
                'SENDING',
                'SENT',
                'FAILED'
            )
        ),
    ADD CONSTRAINT recommendations_email_attempts_check
        CHECK (email_attempts >= 0);

COMMENT ON COLUMN recommendations.email_status IS
    'Recommendation email delivery state used for eligibility, retry and deduplication.';

COMMENT ON COLUMN recommendations.email_sent_at IS
    'When the recommendation email was successfully sent; null when never sent.';

CREATE INDEX recommendations_email_pending_idx
    ON recommendations (email_status, created_at)
    WHERE email_status IN ('PENDING', 'SENDING');