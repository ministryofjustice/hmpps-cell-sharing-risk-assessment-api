-- The next review date goes back on each review (SDIT-4297).
--
-- V4 moved it off csra_review into csra_next_review, one row per prisoner, so the worklist could look it
-- up by prisoner. That threw away the date every earlier review set, and a NOMIS booking move needs it:
-- when the latest reviews move from prisoner A to prisoner B, A's date has to fall back to the one their
-- latest remaining review set, and B's has to become the one the moved review set. Neither can be worked
-- out from a single row per prisoner.
--
-- From here each review carries the date it set, and csra_next_review is re-derived from the reviews by
-- CsraCurrentRatingService.refreshFromReviews rather than written by hand on each path. The table stays,
-- so the worklist and current-rating reads are unchanged.
--
-- Adding a nullable column with no default is a catalogue-only change in Postgres, so this does not
-- rewrite the table (several million rows - see V12).
ALTER TABLE csra_review
    ADD COLUMN next_review_date DATE;

-- Backfill only reviews completed in DPS. A NOMIS review already has its own date on csra_review_nomis,
-- which the refresh falls back to, so the migrated rows are deliberately left alone rather than updated
-- en masse. A DPS review's date only ever lived in csra_next_review, and only for the review that set it;
-- any earlier DPS review's date was overwritten and cannot be recovered.
UPDATE csra_review r
SET next_review_date = n.next_review_date
FROM csra_next_review n
WHERE n.set_by_review_id = r.id
  AND n.next_review_date IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM csra_review_nomis rn WHERE rn.csra_review_id = r.id);

COMMENT ON COLUMN csra_review.next_review_date IS 'The next review date this review set. A DPS assessment sets twelve months on from a high-risk final rating and a DPS review takes the date the reviewer chose; both leave it null for a standard rating. A NOMIS review carries whatever NOMIS recorded, which may be set whatever the rating. Null on NOMIS reviews loaded before this column existed - their date is on csra_review_nomis.next_review_date. [Sensitivity: PERSONAL]';

-- COMMENT ON is last-write-wins and V14 is applied and checksum-frozen, so the corrections land here.
COMMENT ON TABLE csra_next_review IS 'The single next-review-due date currently in force for a prisoner, and what drives the "high risk prisoners due for review" worklist. One row per prisoner. Derived, not written directly: every change to a prisoner''s reviews recomputes it from their latest review with a final rating that is neither archived nor superseded. The date each review set is on csra_review.next_review_date.';
COMMENT ON COLUMN csra_next_review.next_review_date IS 'When the prisoner''s CSRA is next due for review - the date their latest final-rated review set. Null when that review set none. DPS only sets one for a high-risk rating, but a NOMIS review can carry one whatever the rating, so this does not on its own indicate a high-risk rating. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_next_review.set_by_review_id IS 'The csra_review the date was taken from - the prisoner''s latest final-rated, non-archived, non-superseded review. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.next_review_date IS 'The next review date NOMIS recorded on this particular review, kept verbatim. Also copied to csra_review.next_review_date for reviews migrated or synchronised since that column was added; for earlier rows this is the only copy. Null for rows migrated before this column existed. [Sensitivity: PERSONAL]';
