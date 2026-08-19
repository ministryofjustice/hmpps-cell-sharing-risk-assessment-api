ALTER TABLE csra_assessment_stage
    ADD version INTEGER;

ALTER TABLE csra_assessment_stage
    ALTER COLUMN version SET NOT NULL;

COMMENT ON COLUMN csra_assessment_stage.version IS 'Optimistic locking version for concurrent updates [Sensitivity: NONE]';