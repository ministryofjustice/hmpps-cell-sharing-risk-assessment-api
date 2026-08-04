-- Free-text detail captured on a Yes answer, which had nowhere to go.
--
-- Five of the stage's yes/no questions reveal a "Provide details of the risk" box on Yes. The remaining
-- two (officer_spoke_to_prisoner, seen_by_healthcare) are plain yes/no and deliberately have no column.
-- Nullable like every other answer column: null = not answered, which Day 1 allows.
ALTER TABLE csra_assessment_stage
    ADD COLUMN likely_to_harm_cellmate_detail    TEXT,
    ADD COLUMN significantly_vulnerable_detail   TEXT,
    ADD COLUMN cause_for_concern_sharing_detail  TEXT,
    ADD COLUMN other_high_risk_indicators_detail TEXT,
    ADD COLUMN healthcare_increased_risk_detail  TEXT;

-- The evidence behind a Yes to one of the seven offence questions: where it was found, and what it was.
-- Answering Yes leads to a "Where did you find evidence of…?" screen, so a row here implies the offence was
-- answered Yes on the parent stage. The source flags are NOT NULL DEFAULT FALSE — the row only exists
-- because the assessor reached that screen, so an unticked box means "not this source", not "not answered".
CREATE TABLE csra_assessment_stage_offence_evidence
(
    id                  UUID        NOT NULL CONSTRAINT csra_assessment_stage_offence_evidence_pk PRIMARY KEY,
    stage_id            UUID        NOT NULL CONSTRAINT csra_assessment_stage_offence_evidence_stage_fk REFERENCES csra_assessment_stage (id) ON DELETE CASCADE,
    offence             VARCHAR(40) NOT NULL,
    pnc                 BOOLEAN     NOT NULL DEFAULT FALSE,
    warrant             BOOLEAN     NOT NULL DEFAULT FALSE,
    dps                 BOOLEAN     NOT NULL DEFAULT FALSE,
    per                 BOOLEAN     NOT NULL DEFAULT FALSE,
    other               BOOLEAN     NOT NULL DEFAULT FALSE,
    other_source_detail TEXT,
    details             TEXT
);

-- Unique rather than a plain stage_id index: one evidence record per offence per stage. Its leading column
-- is stage_id, so it also serves the "load a stage's evidence" lookup and no second index is needed.
CREATE UNIQUE INDEX csra_assessment_stage_offence_evidence_stage_offence_idx
    ON csra_assessment_stage_offence_evidence (stage_id, offence);
