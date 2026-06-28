-- Additional legacy NOMIS data for a migrated/synchronised CSRA review.
-- 1:0..1 with csra_review: holds only the NOMIS fields not already captured on the core record.
-- The question/answer tree is kept as an opaque JSONB blob, deserialized only when needed.
CREATE TABLE csra_review_nomis
(
    id                         UUID         NOT NULL CONSTRAINT csra_review_nomis_pk PRIMARY KEY,
    csra_review_id             UUID         NOT NULL CONSTRAINT csra_review_nomis_review_fk REFERENCES csra_review (id) ON DELETE CASCADE,
    score                      NUMERIC,
    status                     VARCHAR(10),
    calculated_level           VARCHAR(10),
    review_level               VARCHAR(10),
    approved_level             VARCHAR(10),
    committee_code             VARCHAR(20),
    review_committee_code      VARCHAR(20),
    evaluation_date            DATE,
    evaluation_result_code     VARCHAR(10),
    comment                    TEXT,
    review_comment             TEXT,
    review_committee_comment   TEXT,
    placement_prison_id        VARCHAR(6),
    review_placement_prison_id VARCHAR(6),
    review_details             JSONB
);

CREATE UNIQUE INDEX csra_review_nomis_review_id_idx ON csra_review_nomis (csra_review_id);
