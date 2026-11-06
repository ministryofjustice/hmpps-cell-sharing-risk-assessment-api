ALTER TABLE csra_assessment_stage
    ADD COLUMN provisional_reviewed BOOLEAN;

COMMENT ON COLUMN csra_assessment_stage.provisional_reviewed IS 'Initial assessment FINAL stage only: whether the provisional assessment has been reviewed. NULL = Not yet started, FALSE = Incomplete, TRUE = Complete. [Sensitivity: NONE]';
