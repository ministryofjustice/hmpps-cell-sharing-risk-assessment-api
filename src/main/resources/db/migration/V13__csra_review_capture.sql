-- The review journey's capture, sharing csra_assessment_stage with the initial assessment (MAPA-234).
--
-- Sharing the table keeps one shape for the read side at the cost of journey-specific nulls. It works
-- because the review's eleven questions are close to a subset of the assessment's booleans: the seven
-- offence questions map directly, and the other four map onto likely_to_harm_cellmate,
-- significantly_vulnerable, healthcare_increased_risk and other_high_risk_indicators.
--
-- A review never populates officer_spoke_to_prisoner, cause_for_concern_sharing, seen_by_healthcare or
-- the four evidence booleans; an assessment never populates anything added here. So from here on, a null
-- answer column means EITHER "not answered" OR "not applicable to this journey", and the two are
-- indistinguishable at column level — disambiguate via the parent review's type.

-- Every review answer carries free text on Yes. Four of the eleven questions already got their detail
-- column in V11; these are the seven offence questions, which did not.
ALTER TABLE csra_assessment_stage
    ADD COLUMN offence_murder_manslaughter_detail TEXT,
    ADD COLUMN offence_assisting_suicide_detail   TEXT,
    ADD COLUMN offence_sexual_assault_detail      TEXT,
    ADD COLUMN offence_repeated_violence_detail   TEXT,
    ADD COLUMN offence_prejudice_motivated_detail TEXT,
    ADD COLUMN offence_arson_detail               TEXT,
    ADD COLUMN offence_kidnap_hostage_detail      TEXT;

-- Why the review was held (one of four review types), and who chaired the multidisciplinary meeting.
-- The chair is a free-text name, not a staff reference — there is no lookup behind it.
ALTER TABLE csra_assessment_stage
    ADD COLUMN review_reason   VARCHAR(60),
    ADD COLUMN mdt_chair_name  TEXT;

-- Where the reviewer's evidence came from: a multi-select of named sources, so a child table rather than
-- the four booleans the assessment journey uses. Those booleans do not extend to nineteen sources, and
-- moving the assessment onto this table would mean rewriting existing rows — so the two coexist for now.
--
-- details carries the free text that OTHER requires; it is null for every named source.
CREATE TABLE csra_assessment_stage_evidence_source
(
    id       UUID        NOT NULL CONSTRAINT csra_assessment_stage_evidence_source_pk PRIMARY KEY,
    stage_id UUID        NOT NULL CONSTRAINT csra_assessment_stage_evidence_source_stage_fk REFERENCES csra_assessment_stage (id) ON DELETE CASCADE,
    source   VARCHAR(40) NOT NULL,
    details  TEXT
);

-- Unique rather than a plain stage_id index: a source is either selected or not, so it cannot appear
-- twice on one stage. Its leading column is stage_id, so it also serves the "load a stage's sources"
-- lookup and no second index is needed.
CREATE UNIQUE INDEX csra_assessment_stage_evidence_source_stage_source_idx
    ON csra_assessment_stage_evidence_source (stage_id, source);

-- No DDL for the new INTERIM stage value: stage is VARCHAR(20) with no check constraint, and the unique
-- index (csra_review_id, stage) already permits an INTERIM row alongside a FINAL one on the same review,
-- which is exactly the review journey's shape.
