CREATE TABLE csra_review
(
    id                  UUID         NOT NULL CONSTRAINT csra_review_pk PRIMARY KEY,
    prisoner_number     VARCHAR(10)  NOT NULL,
    prison_id           VARCHAR(6),
    assessment_date     DATE         NOT NULL,
    type                VARCHAR(20)  NOT NULL,
    interim_result      VARCHAR(20),
    interim_result_date DATE,
    final_result        VARCHAR(20),
    final_result_date   DATE,
    next_review_date    DATE,
    created_at          TIMESTAMP    NOT NULL,
    created_by          VARCHAR(40)  NOT NULL,
    last_modified_at    TIMESTAMP,
    last_modified_by    VARCHAR(40)
);

CREATE INDEX csra_review_prisoner_number_idx ON csra_review (prisoner_number);
