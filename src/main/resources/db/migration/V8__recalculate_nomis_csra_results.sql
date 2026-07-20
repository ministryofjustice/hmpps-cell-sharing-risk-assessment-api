-- Re-derive the rating of every migrated/synchronised NOMIS review using the rule NOMIS itself applies
-- (prison-api OffenderAssessment.getClassificationSummary), mirroring NomisCsraReviewMappers.nomisOutcome:
--   1. an approved level that is not PEND is the final, approved rating;
--   2. otherwise the stronger of the reviewer's and the calculated level (HI > STANDARD > MED > LOW);
--   3. otherwise the reviewer's level;
--   4. otherwise the calculated level, unless it is PEND;
--   5. otherwise no rating.
-- Previously we took approved -> review -> calculated with no priority ordering and dropped anything
-- pending, which both lost ratings and could under-state risk (review LOW over calculated HI).
-- An unapproved level has not been through the NOMIS approval step, so it is stored as a provisional
-- (interim) rating rather than a final one.
WITH levels AS (SELECT n.csra_review_id      AS id,
                       cr.assessment_date,
                       n.evaluation_date,
                       n.status,
                       n.calculated_level     AS calc,
                       n.review_level         AS rev,
                       n.approved_level       AS app
                FROM csra_review_nomis n
                         JOIN csra_review cr ON cr.id = n.csra_review_id),
     outcome AS (SELECT id,
                        assessment_date,
                        evaluation_date,
                        -- true only where the approved level stands (step 1) and NOMIS does not still
                        -- hold the review in provisional status
                        (app IS NOT NULL AND app <> 'PEND' AND status IS DISTINCT FROM 'P') AS approved,
                        CASE
                            WHEN app IS NOT NULL AND app <> 'PEND' THEN app
                            WHEN rev IS NOT NULL AND calc IS NOT NULL
                                THEN (SELECT ranked.level
                                      FROM (VALUES ('HI', 1), ('STANDARD', 2), ('MED', 3), ('LOW', 4))
                                               AS ranked(level, rank)
                                      WHERE ranked.level IN (rev, calc)
                                      ORDER BY ranked.rank
                                      LIMIT 1)
                            WHEN rev IS NOT NULL THEN rev
                            WHEN calc IS DISTINCT FROM 'PEND' THEN calc
                            END                                                             AS level
                 FROM levels),
     resolved AS (SELECT id,
                         assessment_date,
                         evaluation_date,
                         approved,
                         CASE
                             WHEN level = 'HI' THEN 'HIGH'
                             WHEN level IN ('STANDARD', 'LOW', 'MED') THEN 'STANDARD'
                             END AS result
                  FROM outcome)
UPDATE csra_review cr
SET final_result        = CASE WHEN r.approved THEN r.result END,
    final_result_date   = CASE
                              WHEN r.approved AND r.result IS NOT NULL
                                  THEN COALESCE(r.evaluation_date, r.assessment_date) END,
    interim_result      = CASE WHEN NOT r.approved THEN r.result END,
    interim_result_date = CASE WHEN NOT r.approved AND r.result IS NOT NULL THEN r.assessment_date END
FROM resolved r
WHERE cr.id = r.id;

-- Rebuild the current-rating projection for the affected prisoners, using the same rule as the V7
-- backfill. Prisoners whose rating was deliberately cleared on readmission are left alone.
WITH affected AS (SELECT DISTINCT cr.prisoner_number
                  FROM csra_review cr
                           JOIN csra_review_nomis n ON n.csra_review_id = cr.id
                  WHERE NOT EXISTS (SELECT 1
                                    FROM csra_current_rating c
                                    WHERE c.prisoner_number = cr.prisoner_number
                                      AND c.set_reason <> 'RATING_SAVED'))
DELETE
FROM csra_current_rating c
    USING affected a
WHERE c.prisoner_number = a.prisoner_number;

INSERT INTO csra_current_rating (id, prisoner_number, rating, provisional, assessment_type, rating_date,
                                 set_by_review_id, set_reason, set_at, set_by)
SELECT gen_random_uuid(),
       latest.prisoner_number,
       COALESCE(latest.final_result, latest.interim_result),
       (latest.final_result IS NULL AND latest.interim_result IS NOT NULL),
       CASE WHEN latest.type IN ('REVIEW', 'CSRA_REVIEW') THEN 'REVIEW' ELSE 'ASSESSMENT' END,
       COALESCE(latest.final_result_date, latest.interim_result_date, latest.assessment_date),
       latest.id,
       'RATING_SAVED',
       now(),
       'SYSTEM'
FROM (SELECT DISTINCT ON (prisoner_number) prisoner_number,
                                           final_result,
                                           interim_result,
                                           type,
                                           final_result_date,
                                           interim_result_date,
                                           assessment_date,
                                           id
      FROM csra_review
      WHERE (final_result IS NOT NULL OR interim_result IS NOT NULL)
        AND status <> 'ARCHIVED'
      ORDER BY prisoner_number, assessment_date DESC, id DESC) latest
WHERE NOT EXISTS (SELECT 1
                  FROM csra_current_rating c
                  WHERE c.prisoner_number = latest.prisoner_number);
