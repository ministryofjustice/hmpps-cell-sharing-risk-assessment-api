-- Lifecycle state for a CSRA review. Previously "in progress" was inferred from final_result IS NULL;
-- an explicit status lets a move close (rating retained) or archive (no rating) an in-progress review
-- without it being confused with a completed or a still-in-progress one.
ALTER TABLE csra_review
    ADD COLUMN status         VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    ADD COLUMN closure_reason VARCHAR(40),
    ADD COLUMN closed_at      TIMESTAMP,
    ADD COLUMN closed_by      VARCHAR(40);

-- Backfill by type, not by result: migrated legacy reviews (and any new-model review with a final
-- result) are historical/COMPLETE; only new-model reviews with no final result are genuinely IN_PROGRESS.
-- Note legacy NOMIS PEND reviews are result-less but still historical, so they must not become IN_PROGRESS.
UPDATE csra_review
SET status = CASE
                 WHEN type IN ('CSRA_INITIAL_REVIEW', 'CSRA_REVIEW') AND final_result IS NULL THEN 'IN_PROGRESS'
                 ELSE 'COMPLETE'
             END;
