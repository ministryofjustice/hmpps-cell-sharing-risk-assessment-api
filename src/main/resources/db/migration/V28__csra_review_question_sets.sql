-- Each review type now asks its own set of questions (MAPA-537). Types 3 and 4 ask six questions that the
-- original eleven-question review set did not, so each gets a Yes/No column and a free-text detail column,
-- like the questions already here. Questions the types share with each other or with the original set reuse
-- their existing columns; the stage's review_reason records which type, and so which wording, was asked.
--
-- mdt_reviewed records the answer to "Has a multidisciplinary team reviewed the prisoner's cell sharing risk?",
-- which only types 3 and 4 ask.
--
-- All nullable: null means not answered, or not asked by this review type (see the note in V13).
ALTER TABLE csra_assessment_stage
    ADD COLUMN mdt_reviewed                         BOOLEAN,
    ADD COLUMN secure_hospital_inpatient            BOOLEAN,
    ADD COLUMN secure_hospital_inpatient_detail     TEXT,
    ADD COLUMN personality_disorder                 BOOLEAN,
    ADD COLUMN personality_disorder_detail          TEXT,
    ADD COLUMN wants_to_harm_or_kill                BOOLEAN,
    ADD COLUMN wants_to_harm_or_kill_detail         TEXT,
    ADD COLUMN strange_or_unusual_behaviour         BOOLEAN,
    ADD COLUMN strange_or_unusual_behaviour_detail  TEXT,
    ADD COLUMN clinical_paranoid_behaviour          BOOLEAN,
    ADD COLUMN clinical_paranoid_behaviour_detail   TEXT,
    ADD COLUMN frequent_cell_change_requests        BOOLEAN,
    ADD COLUMN frequent_cell_change_requests_detail TEXT;

COMMENT ON COLUMN csra_assessment_stage.mdt_reviewed IS 'Has a multidisciplinary team reviewed the prisoner''s cell sharing risk? Asked by the new or additional information and recent change in behaviour or thinking reviews only; true on their final stage. Review journey only. [Sensitivity: NONE]';

COMMENT ON COLUMN csra_assessment_stage.secure_hospital_inpatient IS 'Was the prisoner previously an in-patient at a special hospital or secure unit? Asked by the new or additional information review. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.secure_hospital_inpatient_detail IS 'Free-text detail behind a Yes to the special hospital or secure unit question. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.personality_disorder IS 'Has the prisoner been diagnosed with a personality disorder? Asked by the new or additional information review. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.personality_disorder_detail IS 'Free-text detail behind a Yes to the personality disorder question. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON COLUMN csra_assessment_stage.wants_to_harm_or_kill IS 'Is there any evidence that the prisoner wants to seriously harm or kill someone? Asked by the recent change in behaviour or thinking review. The reviewer''s risk judgement about the person. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.wants_to_harm_or_kill_detail IS 'Free-text detail behind a Yes to the serious harm question. Unstructured, and likely to describe third parties, offending or health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.strange_or_unusual_behaviour IS 'Has any strange or unusual behaviour been observed? Asked by the recent change in behaviour or thinking review. An observation about the person. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.strange_or_unusual_behaviour_detail IS 'Free-text detail behind a Yes to the strange or unusual behaviour question. Unstructured, and likely to describe mental health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.clinical_paranoid_behaviour IS 'Is there clinical evidence of paranoid behaviour, confirmed by a healthcare assessment? Asked by the recent change in behaviour or thinking review. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.clinical_paranoid_behaviour_detail IS 'Free-text detail behind a Yes to the paranoid behaviour question. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.frequent_cell_change_requests IS 'Has the prisoner or their cellmate frequently requested to change cell? Asked by the recent change in behaviour or thinking review. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.frequent_cell_change_requests_detail IS 'Free-text detail behind a Yes to the cell change question. Unstructured, and likely to describe the cellmate as well as the prisoner. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON COLUMN csra_assessment_stage.question_set_version IS 'Version of the question set the stage was captured against, so answers stay interpretable when the questions change. Review stages only; an initial assessment leaves it null. 1 = the original eleven review questions, asked whatever the reason for the review. 2 = a question set for each reason for the review (MAPA-537). [Sensitivity: NONE]';
