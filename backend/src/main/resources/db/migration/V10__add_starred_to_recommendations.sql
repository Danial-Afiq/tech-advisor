-- Ticket 4.4: users can star (save) a recommendation from the dashboard.
--
-- The flag lives on the recommendation itself rather than in a separate table,
-- so it follows the existing lifecycle (AGENTS.md §18.11): supersede-and-insert
-- copies it onto the new ACTIVE row for the same (user, candidate) pair, and a
-- candidate that drops off the shortlist is deleted together with its star.
ALTER TABLE recommendations
    ADD COLUMN starred BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN recommendations.starred IS
    'User saved this recommendation. Carried onto the replacement row when superseded; never used for ranking.';
