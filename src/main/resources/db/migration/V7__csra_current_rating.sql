-- A prisoner's single current CSRA rating: the stateful source of truth for "current rating" (R-06/R-07).
-- Previously the current rating was derived at read time from the latest review; a stored value lets a
-- readmission reset it to "No rating" (rating NULL) even when prior rated reviews exist.
CREATE TABLE csra_current_rating
(
    id               UUID        NOT NULL CONSTRAINT csra_current_rating_pk PRIMARY KEY,
    prisoner_number  VARCHAR(10) NOT NULL,
    rating           VARCHAR(20),           -- NULL = No rating
    provisional      BOOLEAN     NOT NULL DEFAULT FALSE,
    assessment_type  VARCHAR(20),           -- ASSESSMENT / REVIEW; NULL when No rating
    rating_date      DATE,
    set_by_review_id UUID,                  -- NULL on a No-rating reset
    set_reason       VARCHAR(40) NOT NULL,
    set_at           TIMESTAMP   NOT NULL,
    set_by           VARCHAR(40)
);

CREATE UNIQUE INDEX csra_current_rating_prisoner_number_idx ON csra_current_rating (prisoner_number);

-- Backfill from the latest rated, non-archived review per prisoner. assessment_type mirrors
-- CsraType.toAssessmentBucket() (REVIEW / CSRA_REVIEW -> REVIEW, everything else -> ASSESSMENT).
INSERT INTO csra_current_rating (id, prisoner_number, rating, provisional, assessment_type, rating_date,
                                 set_by_review_id, set_reason, set_at, set_by)
SELECT gen_random_uuid(),
       latest.prisoner_number,
       COALESCE(latest.final_result, latest.interim_result),
       (latest.final_result IS NULL AND latest.interim_result IS NOT NULL),
       CASE WHEN latest.type IN ('REVIEW', 'CSRA_REVIEW') THEN 'REVIEW' ELSE 'ASSESSMENT' END,
       COALESCE(latest.final_result_date, latest.assessment_date),
       latest.id,
       'RATING_SAVED',
       now(),
       'SYSTEM'
FROM (SELECT DISTINCT ON (prisoner_number) prisoner_number, final_result, interim_result, type,
                                           final_result_date, assessment_date, id
      FROM csra_review
      WHERE (final_result IS NOT NULL OR interim_result IS NOT NULL)
        AND status <> 'ARCHIVED'
      ORDER BY prisoner_number, assessment_date DESC, id DESC) latest;
