-- A review with no rating can now be cancelled as well as an initial assessment (MAPA-537). Comment only: it
-- reuses the CANCELLED value added for assessments in V22.
COMMENT ON COLUMN csra_review.closure_reason IS 'Why an in-progress review was closed or archived - NOT_COMPLETED_PRISONER_TRANSFER (moved to another establishment), NOT_COMPLETED_PRISONER_RELEASE (readmitted after a period of release), or CANCELLED (an unrated initial assessment or review cancelled by a user). [Sensitivity: PERSONAL]';
