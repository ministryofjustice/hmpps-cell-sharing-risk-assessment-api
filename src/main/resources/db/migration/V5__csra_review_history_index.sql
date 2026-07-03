-- Supports the paginated CSRA history query, which filters by prisoner_number and orders by
-- assessment_date (then id) descending. The composite index lets Postgres satisfy both the filter and
-- the ordering from the index, avoiding a sort of the prisoner's reviews on every page request.
CREATE INDEX csra_review_prisoner_history_idx
    ON csra_review (prisoner_number, assessment_date DESC, id DESC);
