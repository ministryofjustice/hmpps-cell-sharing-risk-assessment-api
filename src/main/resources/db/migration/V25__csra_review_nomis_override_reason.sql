-- Keep NOMIS's override reason (MAPA-500).
--
-- The live prisoner profile shows why an assessor overrode the calculated level. Existing rows have no
-- reason until they are re-migrated or backfilled.
ALTER TABLE csra_review_nomis
    ADD COLUMN override_reason_code VARCHAR(12);

COMMENT ON COLUMN csra_review_nomis.override_reason_code IS 'NOMIS''s OVERRIDE_REASON (domain OVERRIDE_RSN) - the kind of information that led the assessor to override the calculated level: PREVIOUS or SECURITY. Null on rows migrated before it was stored, and where no reason was entered. A few NOMIS rows have a reason with no override; these are stored but not returned. [Sensitivity: PERSONAL]';
