-- Optimistic locking for csra_review (MAPA-437).
--
-- Hibernate writes every column when it saves a review, so two transactions that load the same review and
-- both save it used to end with the second silently overwriting the first - for example a transfer
-- archiving a draft from a stale copy just after a provisional rating was saved. With a version column
-- the second write fails instead (a 409 over HTTP; the SQS listeners retry the message against the
-- current row).
--
-- A constant default is a metadata-only change in Postgres, so the existing rows are not rewritten.
ALTER TABLE csra_review
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN csra_review.version IS 'Optimistic locking version, incremented on every change to the review. A write made from an out-of-date copy of the review is rejected rather than overwriting the newer change. [Sensitivity: NONE]';
