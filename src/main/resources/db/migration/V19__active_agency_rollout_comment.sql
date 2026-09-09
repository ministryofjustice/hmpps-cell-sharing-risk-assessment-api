------------------------------------------------------------------------------------------------
-- active_agency - the API now enforces rollout on user writes (MAPA-363)
--
-- V14 recorded that the API enforced no rollout check. That is no longer true, so the table comment
-- needs correcting. COMMENT ON is last-write-wins and V14 is applied and checksum-frozen, so the
-- correction lands here rather than as an edit to V14. The column comments in V14 are still accurate
-- and are deliberately not restated.
------------------------------------------------------------------------------------------------

COMMENT ON TABLE active_agency IS 'One row per prison that has ever been switched on for CSRA in DPS. The switched-on ids are published unauthenticated on the actuator /info payload, which is how the DPS home page decides whether to show the CSRA tile. Switching a prison off flips the flag rather than deleting the row, so deactivation stays auditable and the toggle is idempotent. The API enforces this list on the user-facing CSRA write endpoints: a write for a prison that is not switched on is rejected with 403 PrisonNotActive, unless the caller holds the rollout override role. The NOMIS migrate/sync endpoints and the movement/merge listeners are deliberately not gated, and reads are open.';
