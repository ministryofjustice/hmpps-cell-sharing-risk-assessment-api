-- Which agencies (prisons) have the CSRA service switched on in DPS. Exposed under /info as
-- "activeAgencies" so the DPS home page knows whether to show the CSRA tile, and used to enforce
-- DPS/NOMIS mutual exclusivity during rollout. A stable row per prison keeps deactivation auditable
-- and the toggle idempotent, so a prison switched off retains who switched it and when.
CREATE TABLE active_agency
(
    id         UUID        NOT NULL CONSTRAINT active_agency_pk PRIMARY KEY,
    agency_id  VARCHAR(6)  NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP   NOT NULL,
    updated_by VARCHAR(40)
);

CREATE UNIQUE INDEX active_agency_agency_id_idx ON active_agency (agency_id);
