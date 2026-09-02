-- Marks a review as belonging to a previous period of custody.
--
-- On a readmission after release the current rating is reset to "No rating" (R-01), but that reset lived
-- only in the csra_current_rating projection: refreshFromReviews re-derives the rating from csra_review
-- and excludes only ARCHIVED rows, so the prisoner's pre-release COMPLETE/CLOSED reviews were still
-- eligible and the next NOMIS migrate or sync silently restored the pre-release rating.
--
-- Stamping the reviews themselves rather than watermarking the projection keeps the boundary in the data,
-- where it survives a projection rebuild. NB any future recalculation migration that rebuilds
-- csra_current_rating must carry "AND superseded_at IS NULL" alongside its "status <> 'ARCHIVED'", or it
-- will resurrect exactly what this hides - see V7 and V8 for the shape of that query.
--
-- Not backfilled: resetToNoRating and refreshFromReviews overwrite the projection row in place, so a
-- prisoner whose reset was already undone carries no trace of it and cannot be identified.
ALTER TABLE csra_review
    ADD COLUMN superseded_at TIMESTAMP;

COMMENT ON COLUMN csra_review.superseded_at IS 'When this review was superseded by the prisoner being readmitted after a period of release, ending the custody period it belongs to. Null for a review in the current period. A superseded review is still shown in the prisoner''s history but can never set the current rating. [Sensitivity: NONE]';
