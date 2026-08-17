-- Tracks the last partial save of a stage (before the officer confirms the rating).
-- completedBy / completedAt mean "this stage was confirmed with a rating"; these two columns
-- cover the earlier state, allowing a resumed draft to show who last edited each section
-- without conflating a save with a confirmation.
ALTER TABLE csra_assessment_stage
    ADD COLUMN last_saved_by  VARCHAR(255),
    ADD COLUMN last_saved_at  TIMESTAMP;

COMMENT ON COLUMN csra_assessment_stage.last_saved_by IS 'Username that last saved the stage without confirming a rating. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN csra_assessment_stage.last_saved_at IS 'When the stage was last saved without confirming a rating. [Sensitivity: NONE]';
