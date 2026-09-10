------------------------------------------------------------------------------------------------
-- csra_review.type - rename two CsraType values (MAPA-367)
--
--   CSRA_INITIAL_REVIEW -> CSRA_INITIAL_ASSESSMENT   the new DPS initial journey is an assessment,
--                                                    not a review; the old name was read as a review
--                                                    by everyone who met it
--   REVIEW              -> NOMIS_REVIEW              the legacy NOMIS review, renamed so it cannot be
--                                                    confused with the new-model CSRA_REVIEW
--
-- Done now rather than later because the cost only grows: production has no prison switched on for
-- CSRA, so there are almost no new-model rows there and no user sees the service.
--
-- CSRA_INITIAL_ASSESSMENT is 23 characters and this column was VARCHAR(20) (V1__csra_review.sql:7),
-- so it has to be widened. Nothing in the running application would have caught that: ddl-auto is
-- none and generate-ddl is false, so Hibernate never validates the column against the entity, and
-- the first symptom would have been SQLSTATE 22001 on the next assessment start. The old longest
-- value was CSRA_INITIAL_REVIEW at 19 characters, which is why VARCHAR(20) survived this long.
-- VARCHAR(40) matches the sibling columns (created_by, last_modified_by) and leaves headroom.
--
-- The widen and both UPDATEs are deliberately one migration. Flyway runs a migration in a single
-- transaction, so this is all-or-nothing, and a half-applied rename is the state to avoid: the enum
-- in this release has neither old value, so any row still holding one fails *every* read with
-- "No enum constant CsraType.X" out of @Enumerated(EnumType.STRING) on CsraReviewEntity.type.
--
-- Both UPDATEs are WHERE-filtered rather than unconditional, which is what makes the manual path in
-- docs/csra-type-rename-runbook.md work: if the data was already renamed by hand ahead of the
-- deploy, these match nothing and the migration is instant.
--
-- 'REVIEW' is a legal and unrelated value elsewhere in this schema and is NOT swept up here:
--   * csra_current_rating.assessment_type holds the ASSESSMENT/REVIEW bucket (V7:10)
--   * csra_review_nomis.committee_code / review_committee_code hold the NOMIS committee code
--     'Review Board' (V2:13-14, CsraCommitteeCode.REVIEW)
-- csra_review.type is the only column in this database holding a CsraType.
--
-- V6:15, V7:28, V8:79 and V18:27 name the old values. They are applied and checksum-frozen, they are
-- correct as a record of what ran at the time, and they are deliberately not edited - the same
-- reasoning V19 sets out for the V14 table comment. Only the live COMMENT ON is re-issued.
------------------------------------------------------------------------------------------------

-- Fail fast rather than queue behind a long reader and block every query behind this ALTER.
SET LOCAL lock_timeout = '5s';

ALTER TABLE csra_review
    ALTER COLUMN type TYPE VARCHAR(40);

-- New-model rows. Very few: DPS rollout has not started.
UPDATE csra_review
SET type = 'CSRA_INITIAL_ASSESSMENT'
WHERE type = 'CSRA_INITIAL_REVIEW';

-- Legacy NOMIS rows - a large share of the migrated population, and the reason the runbook exists.
UPDATE csra_review
SET type = 'NOMIS_REVIEW'
WHERE type = 'REVIEW';

COMMENT ON COLUMN csra_review.type IS 'The kind of assessment. CSRA_INITIAL_ASSESSMENT and CSRA_REVIEW are the new DPS journeys; FULL, HEALTH, LOCATE, RATING, RECEPTION and NOMIS_REVIEW are legacy NOMIS types mapped on during migration. Renamed from CSRA_INITIAL_REVIEW and REVIEW in V20 - migrations before V20 and audit records written before it use the old names. [Sensitivity: NONE]';
