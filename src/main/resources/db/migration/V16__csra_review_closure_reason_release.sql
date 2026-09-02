-- A CSRA closed on a readmission after release was recorded as "not completed due to prisoner transfer",
-- because closure_reason had only that one value. MAPA-337 requires the two to be distinguished, so a
-- release-specific value now exists. No DDL is needed - closure_reason is an unconstrained VARCHAR - but
-- the published data dictionary named the single value, so its description has to be re-issued.
--
-- Rows closed before this lands still read NOT_COMPLETED_PRISONER_TRANSFER whichever movement ended them:
-- the movement type is not recorded on the row, so historic readmissions cannot be told apart and are
-- deliberately not backfilled.
COMMENT ON COLUMN csra_review.closure_reason IS 'Why an in-progress review was closed or archived - NOT_COMPLETED_PRISONER_TRANSFER (moved to another establishment) or NOT_COMPLETED_PRISONER_RELEASE (readmitted after a period of release). Its presence indicates the prisoner moved mid-assessment. [Sensitivity: PERSONAL]';
