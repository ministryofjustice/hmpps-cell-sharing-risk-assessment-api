-- Keep NOMIS's override level separately (MAPA-468).
--
-- prison-api resolves a NOMIS CSRA by letting the review level win outright, and only otherwise comparing
-- the override level with the calculated level. Until now the override was not received, so that
-- comparison could not be made. Existing rows have no override level; they are put right by re-migrating.
ALTER TABLE csra_review_nomis
    ADD COLUMN override_level VARCHAR(10);

COMMENT ON COLUMN csra_review_nomis.override_level IS 'NOMIS''s OVERRIDED_SUP_LEVEL_TYPE - the level the assessor chose in place of the calculated level. Where there is no review level, the stronger of this and calculated_level is the rating. Null on rows migrated before it was stored. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';

COMMENT ON COLUMN csra_review_nomis.calculated_level IS 'NOMIS''s CALC_SUP_LEVEL_TYPE - the CSRA level NOMIS calculated from the answers, before any override. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';

COMMENT ON COLUMN csra_review_nomis.review_level IS 'NOMIS''s REVIEW_SUP_LEVEL_TYPE - what NOMIS displays as the approved result. Where set and not PEND it is the rating, whatever the calculated or override level. Do not confuse with approved_level. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';
