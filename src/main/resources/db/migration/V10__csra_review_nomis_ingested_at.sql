-- When a NOMIS review was last written by migration or sync, on our clock.
--
-- csra_review.created_at holds NOMIS's own creation timestamp (values go back to 2006), and nothing
-- anywhere recorded when the data actually reached us. That made "which migration run produced this
-- state?" unanswerable while diagnosing MAPA-253, where dev and preprod held contradictory results from
-- the same source data and only the load history could have explained it.
--
-- Nullable and not backfilled on purpose: for rows already loaded we genuinely do not know, and a
-- default would assert something untrue. NULL means "arrived before we started recording this".
--
-- Adding a nullable column with no default is a catalogue-only change in Postgres, so this does not
-- rewrite the table -- important on a table of several million rows, given V8's migration lock stopped
-- pods becoming healthy and had to be run by hand.
ALTER TABLE csra_review_nomis
    ADD COLUMN ingested_at TIMESTAMP;

COMMENT ON COLUMN csra_review_nomis.ingested_at IS
    'When this row was last written by migration or sync (our clock, not NOMIS''s). NULL = predates the column.';
