-- Captured answer set for the new (DPS) initial CSRA assessment.
-- A review has a 1:0..2 relationship to its stages: a PROVISIONAL (Day 1) and/or FINAL (Day 2) stage,
-- unique per stage. Answer columns are typed and nullable (null = not answered, which Day 1 allows).
-- The stage rating is not stored here; it is derived from csra_review interim/final result.
CREATE TABLE csra_assessment_stage
(
    id                          UUID        NOT NULL CONSTRAINT csra_assessment_stage_pk PRIMARY KEY,
    csra_review_id              UUID        NOT NULL CONSTRAINT csra_assessment_stage_review_fk REFERENCES csra_review (id) ON DELETE CASCADE,
    stage                       VARCHAR(20) NOT NULL,
    completed_by                VARCHAR(40),
    completed_at                TIMESTAMP,
    prison_id                   VARCHAR(6),
    assessment_comment          TEXT,
    question_set_version        INTEGER,
    -- evidence sources checked
    dps_checked                 BOOLEAN,
    per_checked                 BOOLEAN,
    warrant_checked             BOOLEAN,
    pnc_checked                 BOOLEAN,
    -- offence flags
    offence_murder_manslaughter BOOLEAN,
    offence_assisting_suicide   BOOLEAN,
    offence_sexual_assault      BOOLEAN,
    offence_repeated_violence   BOOLEAN,
    offence_prejudice_motivated BOOLEAN,
    offence_arson               BOOLEAN,
    offence_kidnap_hostage      BOOLEAN,
    -- prisoner conversation and vulnerability
    officer_spoke_to_prisoner   BOOLEAN,
    likely_to_harm_cellmate     BOOLEAN,
    significantly_vulnerable    BOOLEAN,
    -- officer observation / other indicators
    cause_for_concern_sharing   BOOLEAN,
    other_high_risk_indicators  BOOLEAN,
    -- healthcare assessment
    seen_by_healthcare          BOOLEAN,
    healthcare_increased_risk   BOOLEAN
);

CREATE UNIQUE INDEX csra_assessment_stage_review_stage_idx ON csra_assessment_stage (csra_review_id, stage);

-- "Who is this person a risk to?" selections for a high-risk stage (0..n).
CREATE TABLE csra_assessment_stage_risk_to
(
    id       UUID        NOT NULL CONSTRAINT csra_assessment_stage_risk_to_pk PRIMARY KEY,
    stage_id UUID        NOT NULL CONSTRAINT csra_assessment_stage_risk_to_stage_fk REFERENCES csra_assessment_stage (id) ON DELETE CASCADE,
    category VARCHAR(30) NOT NULL,
    details  TEXT
);

CREATE INDEX csra_assessment_stage_risk_to_stage_idx ON csra_assessment_stage_risk_to (stage_id);

-- "Is this person part of a vulnerable or at-risk group?" selections for a high-risk stage (0..n).
CREATE TABLE csra_assessment_stage_vulnerability
(
    id       UUID        NOT NULL CONSTRAINT csra_assessment_stage_vulnerability_pk PRIMARY KEY,
    stage_id UUID        NOT NULL CONSTRAINT csra_assessment_stage_vulnerability_stage_fk REFERENCES csra_assessment_stage (id) ON DELETE CASCADE,
    category VARCHAR(30) NOT NULL,
    details  TEXT
);

CREATE INDEX csra_assessment_stage_vulnerability_stage_idx ON csra_assessment_stage_vulnerability (stage_id);
