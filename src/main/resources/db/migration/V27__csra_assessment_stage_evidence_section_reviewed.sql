ALTER TABLE csra_assessment_stage
    ADD COLUMN offence_evidence_reviewed BOOLEAN;

COMMENT ON COLUMN csra_assessment_stage.offence_evidence_reviewed IS 'Initial assessment FINAL stage only: whether all evidence questions have been answered for all selected offences. NULL = Not yet started, FALSE = Incomplete (user has added/removed evidence sources without answering questions), TRUE = Complete (all offence evidence details provided). Set to FALSE when evidence sources change, TRUE when the last evidence detail is submitted. [Sensitivity: NONE]';
