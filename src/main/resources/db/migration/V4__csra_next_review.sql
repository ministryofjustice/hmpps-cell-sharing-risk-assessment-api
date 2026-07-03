-- The single current "next review due" date per prisoner.
-- A prisoner has many CSRA reviews over time but only one outstanding next review date, recalculated on
-- each review. Moved off csra_review (which held it per review) so worklists can look it up per prisoner.
CREATE TABLE csra_next_review
(
    id               UUID        NOT NULL CONSTRAINT csra_next_review_pk PRIMARY KEY,
    prisoner_number  VARCHAR(10) NOT NULL,
    next_review_date DATE,
    set_by_review_id UUID        NOT NULL CONSTRAINT csra_next_review_review_fk REFERENCES csra_review (id) ON DELETE CASCADE,
    updated_at       TIMESTAMP   NOT NULL,
    updated_by       VARCHAR(40)
);

CREATE UNIQUE INDEX csra_next_review_prisoner_number_idx ON csra_next_review (prisoner_number);

-- Backfill from existing data: the prisoner's latest review carries the current next review date.
INSERT INTO csra_next_review (id, prisoner_number, next_review_date, set_by_review_id, updated_at)
SELECT gen_random_uuid(), latest.prisoner_number, latest.next_review_date, latest.id, now()
FROM (SELECT DISTINCT ON (prisoner_number) prisoner_number, next_review_date, id
      FROM csra_review
      ORDER BY prisoner_number, assessment_date DESC, id DESC) latest
WHERE latest.next_review_date IS NOT NULL;

-- The per-review column is superseded by the per-prisoner table above.
ALTER TABLE csra_review
    DROP COLUMN next_review_date;
