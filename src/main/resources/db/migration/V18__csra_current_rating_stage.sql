-- Which stage the current rating came from: FINAL, PROVISIONAL or INTERIM.
--
-- csra_current_rating.provisional says a rating is not yet confirmed, but not what the user was told it
-- was called. Both journeys write their unconfirmed rating to csra_review.interim_result - an initial
-- assessment's Day 1 PROVISIONAL stage and a review's first-sitting INTERIM stage alike - so the boolean
-- cannot tell "HIGH RISK GENERAL (PROVISIONAL)" from "HIGH RISK GENERAL (INTERIM)". The screens name and
-- sort the two differently, so the distinction has to reach the front end.
--
-- Denormalised here rather than looked up from csra_assessment_stage because the prison prisoner list
-- reads this table alone, and because a migrated NOMIS review has no stage row at all.
ALTER TABLE csra_current_rating
    ADD COLUMN rating_stage VARCHAR(20);

COMMENT ON COLUMN csra_current_rating.rating_stage IS 'Which stage the current rating came from - FINAL (confirmed), PROVISIONAL (an initial assessment''s Day 1 rating) or INTERIM (a review''s first-sitting rating, issued before the multidisciplinary team has met). Null when there is no rating. A narrower form of the provisional flag, which cannot distinguish the last two. [Sensitivity: NONE]';

-- Backfill from the review each row already points at, rather than re-deriving "the latest rated review"
-- the way V7 and V8 do. That keeps this migration out of the superseded_at trap described in V17, and
-- correctly leaves rating_stage null for a row reset to "No rating" (set_by_review_id is null there).
--
-- Mirrors ratingStageFor(): only the new-model CSRA_REVIEW produces an interim rating. The legacy NOMIS
-- REVIEW type also carries an interim_result when NOMIS still holds it in provisional status, and that is
-- provisional, not interim - which is why this keys off the full type and not the ASSESSMENT/REVIEW bucket.
UPDATE csra_current_rating cur
SET rating_stage = CASE
                       WHEN r.final_result IS NOT NULL THEN 'FINAL'
                       WHEN r.interim_result IS NULL THEN NULL
                       WHEN r.type = 'CSRA_REVIEW' THEN 'INTERIM'
                       ELSE 'PROVISIONAL'
                   END
FROM csra_review r
WHERE r.id = cur.set_by_review_id;
