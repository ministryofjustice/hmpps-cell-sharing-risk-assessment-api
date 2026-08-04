-- The next review date NOMIS recorded on this particular review.
--
-- We have always received it (NomisCsraReview.nextReviewDate) but only ever stored it in csra_next_review,
-- which holds one row PER PRISONER — the single date currently in force. That is what the service needs to
-- drive "high risk due for review", but it means a historic review's own next-review date was discarded.
-- The legacy review detail screen shows it per review, so it has to live beside the review.
--
-- Deliberately NOT backfilled from csra_next_review: that table holds one date per prisoner, so copying it
-- onto every review would stamp a 2013 review with today's date. A blank is honest; a wrong date is not.
-- Rows loaded before this column stay NULL until a re-migration, and the screen shows "Not entered" —
-- exactly what the DPS profile screen does when prison-api has no date.
--
-- Adding a nullable column with no default is a catalogue-only change in Postgres, so this does not rewrite
-- the table -- important on a table of several million rows, given V8's migration lock stopped pods
-- becoming healthy and had to be run by hand.
ALTER TABLE csra_review_nomis
    ADD COLUMN next_review_date DATE;

COMMENT ON COLUMN csra_review_nomis.next_review_date IS
    'Next review date as recorded by NOMIS on this review. NULL = migrated before this column existed; do not substitute csra_next_review, which is per-prisoner.';
