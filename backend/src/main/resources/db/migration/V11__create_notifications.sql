-- Ticket 4.4.2: in-app notifications.
--
-- One row groups the genuinely new or changed recommendations from one
-- evaluation of one device ("We found 3 new upgrade options for your iPhone
-- 13"). Unchanged re-runs create nothing. The recommendation's details stay in
-- `recommendations`; only the text shown in the bell is stored here.
--
-- recommendation_id points at the recommendation to open (the best one in the
-- group). Supersede-and-insert re-points it onto the replacement row, so the
-- link keeps opening the live card; a deleted recommendation (dropped off the
-- shortlist) leaves NULL and the UI shows it as no longer available.
CREATE TABLE notifications (
    id                BIGSERIAL   PRIMARY KEY,
    user_id           BIGINT      NOT NULL
                      REFERENCES users (id) ON DELETE CASCADE,
    type              TEXT        NOT NULL,
    title             TEXT        NOT NULL,
    message           TEXT        NOT NULL,
    recommendation_id BIGINT
                      REFERENCES recommendations (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at           TIMESTAMPTZ
);

COMMENT ON COLUMN notifications.type IS
    'Notification kind; currently only NEW_RECOMMENDATION.';
COMMENT ON COLUMN notifications.read_at IS
    'NULL while unread; when the user first read it otherwise.';

CREATE INDEX notifications_user_created_idx
    ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX notifications_user_unread_idx
    ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX notifications_recommendation_idx
    ON notifications (recommendation_id);
