ALTER TABLE csra_assessment_stage
    ADD version INTEGER;

ALTER TABLE csra_assessment_stage
    ALTER COLUMN version SET NOT NULL;