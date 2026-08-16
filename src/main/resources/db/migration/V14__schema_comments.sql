-- Data dictionary for the CSRA schema.
--
-- These comments are read by SchemaSpy (published to GitHub Pages) and by anything else that reads
-- pg_description, including the CSV export for the MOJ Data Catalogue / Glue. Keep them updated when
-- columns are added or their meaning changes - SchemaCommentsTest fails the build if a table or column
-- has no comment.
--
-- Every column comment ends with a sensitivity classification:
--
--   [Sensitivity: NONE]                - not personal data in itself (keys, timestamps, process flags)
--   [Sensitivity: PERSONAL]            - personal data about a prisoner: identifies or locates them, or
--                                        is a risk judgement about them
--   [Sensitivity: STAFF]               - personal data about a member of staff, typically the username
--                                        that performed an action
--   [Sensitivity: SPECIAL-CATEGORY]    - UK GDPR Article 9 data (health, sexuality, religion, race,
--                                        gender reassignment) or criminal offence data under Article 10
--   [Sensitivity: OFFICIAL-SENSITIVE]  - not personal data, but damaging if disclosed
--
-- STAFF is still personal data and still in scope for a staff member's own subject access request. It is
-- separated from PERSONAL so that an extract about prisoners can be reasoned about without staff columns
-- inflating the count, and so staff data can be dropped or pseudonymised independently. Note that
-- mdt_chair_name is STAFF rather than PERSONAL despite being free text: it names whoever chaired the
-- meeting, not the prisoner.
--
-- Three things to understand before using these classifications:
--
--   1. They describe the column's own content, not the row's. Every row in this schema belongs to a
--      prisoner, so the whole record is personal data about that prisoner whatever an individual column
--      is marked - that is what matters for a subject access request.
--   2. **This schema holds a lot of special category data.** A CSRA asks directly about offending
--      (murder, sexual assault, arson), about healthcare, and about whether the person belongs to a
--      vulnerable group defined by disability, mental health, sexual orientation, gender reassignment,
--      ethnicity or religion. The seven offence questions are criminal offence data under Article 10
--      whichever way they are answered - a recorded "No" is still offence data about that person.
--   3. **Every free-text column should be assumed to contain more than its question asks.** The detail
--      boxes invite an assessor to describe risk in their own words, and in practice that means health,
--      offending and third-party names. They are classified on that basis, not on the question label.
--
-- Note on nulls in csra_assessment_stage: the table is shared by the initial assessment journey and the
-- review journey, so a null answer means EITHER "not answered" OR "not asked on this journey". The two
-- are indistinguishable at column level - narrow by the parent review's type first.

------------------------------------------------------------------------------------------------
-- csra_review - the core review record
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_review IS 'One cell sharing risk assessment or review for a prisoner. Holds only what is common to both the new DPS journeys and reviews migrated from NOMIS; legacy-only NOMIS detail hangs off csra_review_nomis and the new-model captured answers off csra_assessment_stage. A prisoner accumulates many of these over time - the one that counts today is the projection in csra_current_rating.';

COMMENT ON COLUMN csra_review.id IS 'Primary key. Time-ordered UUID v7, so insert order matches id order. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.prisoner_number IS 'NOMIS offender number (noms id) of the prisoner assessed. The link that makes every row here personal data about that prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review.prison_id IS 'Agency (prison) code where the assessment took place - for a multi-stage assessment, its latest stage. Null identifies a row migrated from NOMIS, which does not always send it; every DPS write path requires it. Indicates where the prisoner was held. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review.assessment_date IS 'Date the assessment or review was started. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.type IS 'The kind of assessment. CSRA_INITIAL_REVIEW and CSRA_REVIEW are the new DPS journeys; FULL, HEALTH, LOCATE, RATING, RECEPTION and REVIEW are legacy NOMIS types mapped on during migration. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.interim_result IS 'The rating issued at the first stage, when the assessment or review could not be completed in one sitting - HIGH, HIGH_GENERAL, HIGH_SPECIFIC or STANDARD. Not set for migrated legacy reviews. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review.interim_result_date IS 'Date the interim or provisional rating was issued. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.final_result IS 'The rating that completed the assessment or review - HIGH (legacy NOMIS, no general/specific split), HIGH_GENERAL (cannot share with anyone), HIGH_SPECIFIC (can share only with certain prisoners) or STANDARD (can share). A risk judgement about the prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review.final_result_date IS 'Date the final rating was issued. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.status IS 'Lifecycle state. IN_PROGRESS (started, no final rating), COMPLETE (final rating recorded, and the state of every migrated review), CLOSED (in progress with a rating that still stands, closed out by a move), ARCHIVED (in progress with no rating, hidden from the service but retained for investigation). [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.closure_reason IS 'Why an in-progress review was closed or archived - currently only NOT_COMPLETED_PRISONER_TRANSFER. Its presence indicates the prisoner moved establishment mid-assessment. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review.closed_at IS 'When the review was closed or archived. Null while it is still open or was completed normally. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.closed_by IS 'Username that closed or archived the review, or the system user when a prisoner movement did it. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN csra_review.created_at IS 'When the review record was created. For migrated rows this is NOMIS''s own creation timestamp, which is why values go back to 2006 - see csra_review_nomis.ingested_at for when the data actually reached this service. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.created_by IS 'Username that created the review, or the system user for migrated and synchronised rows. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN csra_review.last_modified_at IS 'When the review was last changed. Null if it has not been changed since creation. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review.last_modified_by IS 'Username that last changed the review. Identifies a member of staff. [Sensitivity: STAFF]';

------------------------------------------------------------------------------------------------
-- csra_assessment_stage - the captured answers for a new (DPS) assessment or review
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_assessment_stage IS 'The answers captured at one stage of a new (DPS) CSRA. A review record has up to two: an initial assessment has PROVISIONAL (Day 1) and/or FINAL (Day 2), a review has INTERIM and/or FINAL. Both journeys share this table, so a null answer means either "not answered" or "not asked on this journey" - narrow by the parent review''s type before counting unanswered questions. The stage''s rating is not stored here; it lives on csra_review. Holds the most sensitive data in the service: offence questions, healthcare answers and free-text risk descriptions.';

COMMENT ON COLUMN csra_assessment_stage.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.csra_review_id IS 'Foreign key to csra_review - the assessment or review this stage belongs to. Unique per stage. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.stage IS 'Which stage this is. PROVISIONAL (initial assessment, Day 1), INTERIM (review, first sitting) or FINAL (completes either journey). A review carries PROVISIONAL or INTERIM, never both. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.completed_by IS 'Username of the assessor who submitted this stage. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN csra_assessment_stage.completed_at IS 'When this stage was submitted. Null while it is still a draft. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.prison_id IS 'Agency code where this stage was completed. Indicates where the prisoner was held at the time. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.assessment_comment IS 'The assessor''s free-text comment on the assessment. Unstructured and unbounded - in practice describes offending, behaviour, health and third parties, so treat as special category regardless of what any individual comment happens to say. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.question_set_version IS 'Version of the question set the stage was captured against, so answers stay interpretable when the questions change. [Sensitivity: NONE]';

COMMENT ON COLUMN csra_assessment_stage.dps_checked IS 'Whether the assessor checked DPS (current and historical adjudications) as an evidence source. Records which sources were consulted, not what was found. Initial assessment journey only. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.per_checked IS 'Whether the assessor checked the Person Escort Record as an evidence source. Initial assessment journey only. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.warrant_checked IS 'Whether the assessor checked the warrant (current charge or offence) as an evidence source. Initial assessment journey only. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.pnc_checked IS 'Whether the assessor checked the PNC (current and previous convictions) as an evidence source. Initial assessment journey only. [Sensitivity: NONE]';

COMMENT ON COLUMN csra_assessment_stage.offence_murder_manslaughter IS 'Is there evidence of murder, manslaughter or a life-threatening assault on another prisoner in custody? Criminal offence data about the prisoner whichever way it is answered - a recorded No is still offence data. Selecting Yes forces a HIGH_GENERAL rating. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_assisting_suicide IS 'Is there evidence of assisting a suicide in custody? Criminal offence data whichever way it is answered. Selecting Yes forces a HIGH_GENERAL rating. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_sexual_assault IS 'Is there evidence of sexual assault of a same-sex adult victim? Criminal offence data whichever way it is answered. Selecting Yes forces a HIGH_GENERAL rating. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_repeated_violence IS 'Is there evidence of repeated violence in custody? Criminal offence data whichever way it is answered. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_prejudice_motivated IS 'Is there evidence of offending or behaviour motivated by prejudice? Criminal offence data, and an answer that may reveal the victim''s race, religion or sexual orientation. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_arson IS 'Is there evidence of arson or fire setting? Criminal offence data whichever way it is answered. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_kidnap_hostage IS 'Is there evidence of kidnap, hostage taking or false imprisonment? Criminal offence data whichever way it is answered. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON COLUMN csra_assessment_stage.offence_murder_manslaughter_detail IS 'Free-text detail behind a Yes to the murder or manslaughter question. Review journey only - an initial assessment records its evidence in csra_assessment_stage_offence_evidence instead. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_assisting_suicide_detail IS 'Free-text detail behind a Yes to the assisting suicide question. Review journey only. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_sexual_assault_detail IS 'Free-text detail behind a Yes to the sexual assault question. Review journey only. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_repeated_violence_detail IS 'Free-text detail behind a Yes to the repeated violence question. Review journey only. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_prejudice_motivated_detail IS 'Free-text detail behind a Yes to the prejudice-motivated question. Likely to describe the race, religion or sexual orientation of a victim as well as the prisoner''s own behaviour. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_arson_detail IS 'Free-text detail behind a Yes to the arson question. Review journey only. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.offence_kidnap_hostage_detail IS 'Free-text detail behind a Yes to the kidnap or hostage question. Review journey only. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON COLUMN csra_assessment_stage.officer_spoke_to_prisoner IS 'Did the officer speak to the prisoner as part of the assessment? A plain yes/no about process, with no detail box behind it. Initial assessment journey only. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage.likely_to_harm_cellmate IS 'Is the prisoner likely to harm a cellmate? The assessor''s risk judgement about the person. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.likely_to_harm_cellmate_detail IS 'Free text describing the risk of harm to a cellmate, shown when the answer is Yes. Expect offending, behaviour and named third parties. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.significantly_vulnerable IS 'Is the prisoner significantly vulnerable? Vulnerability here is routinely a matter of disability, mental health or belonging to an at-risk group. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.significantly_vulnerable_detail IS 'Free text describing the prisoner''s vulnerability, shown when the answer is Yes. Expect health, disability and sexual orientation. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.cause_for_concern_sharing IS 'Is there any other cause for concern about this person sharing a cell? The assessor''s risk judgement about the person. Initial assessment journey only. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.cause_for_concern_sharing_detail IS 'Free text describing the cause for concern, shown when the answer is Yes. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.other_high_risk_indicators IS 'Are there other indicators of high risk? The assessor''s risk judgement about the person. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.other_high_risk_indicators_detail IS 'Free text describing the other high-risk indicators, shown when the answer is Yes. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.seen_by_healthcare IS 'Has the prisoner been seen by healthcare as part of this assessment? Data concerning health. Initial assessment journey only. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.healthcare_increased_risk IS 'Did healthcare identify anything that increases the risk of sharing a cell? Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage.healthcare_increased_risk_detail IS 'Free text describing what healthcare identified, shown when the answer is Yes. Data concerning health. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON COLUMN csra_assessment_stage.review_reason IS 'Why the review was held - SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW, SHORT_TERM_HIGH_RISK_REVIEW, NEW_OR_ADDITIONAL_INFORMATION or RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING. Captured on every review including Standard risk. Review journey only; an initial assessment leaves it null. Some values are a statement about the prisoner''s behaviour. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage.mdt_chair_name IS 'Free-text name of whoever chaired the multidisciplinary meeting. A name typed by hand, with no staff lookup behind it. Review journey only. [Sensitivity: STAFF]';

------------------------------------------------------------------------------------------------
-- csra_assessment_stage_offence_evidence - evidence behind a Yes to an offence question
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_assessment_stage_offence_evidence IS 'The evidence behind a Yes to one of the seven offence questions on an initial assessment stage: where it was found, and free text describing it. At most one row per stage and offence. Unlike the answer columns on the parent, the source flags are non-null - a row only exists because the assessor reached the "where did you find evidence of...?" screen, so an unticked box means "not this source", not "not answered". The review journey records the same thing in the offence detail columns on the parent instead.';

COMMENT ON COLUMN csra_assessment_stage_offence_evidence.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.stage_id IS 'Foreign key to csra_assessment_stage. Deleted with the stage. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.offence IS 'Which offence question this evidence supports - MURDER_MANSLAUGHTER, ASSISTING_SUICIDE, SEXUAL_ASSAULT, REPEATED_VIOLENCE, PREJUDICE_MOTIVATED, ARSON or KIDNAP_HOSTAGE. The row exists only because that question was answered Yes, so it is criminal offence data about the prisoner. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.pnc IS 'Whether the evidence came from the PNC (current and previous convictions). [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.warrant IS 'Whether the evidence came from the warrant (current charge or offence). [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.dps IS 'Whether the evidence came from DPS (current and historical adjudications). [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.per IS 'Whether the evidence came from the Person Escort Record. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.other IS 'Whether the evidence came from a source not in the list, named in other_source_detail. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.other_source_detail IS 'Free text naming the source when "other" is ticked. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage_offence_evidence.details IS 'Free text describing what the evidence actually was. Describes the prisoner''s offending in the assessor''s own words. [Sensitivity: SPECIAL-CATEGORY]';

------------------------------------------------------------------------------------------------
-- csra_assessment_stage_evidence_source - sources consulted during a review
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_assessment_stage_evidence_source IS 'One evidence source the reviewer consulted, from the review journey''s multi-select. One row per selected source, unique per stage and source. A different question from the offence-evidence table: this records which sources were looked at, not where a specific piece of evidence came from. Review journey only - an initial assessment uses the four *_checked booleans on the parent, which do not extend to a list this long.';

COMMENT ON COLUMN csra_assessment_stage_evidence_source.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_evidence_source.stage_id IS 'Foreign key to csra_assessment_stage. Deleted with the stage. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_evidence_source.source IS 'The source consulted - one of ALLOCATION_BOARD, ASSET_PLUS, DPS, HEALTHCARE_ASSESSMENT, INCENTIVES_REVIEW, MAPPA_REVIEW, INTELLIGENCE_MANAGEMENT_SERVICE, OASYS, PLACEMENT_REVIEW_FORM, PNC, RECATEGORISATION_REVIEW, ROTL_BOARD, SAFETY_AND_SECURITY_FORM, SAFETY_DIAGNOSTIC_TOOL, SECURITY_FILE, SENTENCE_PLAN, TRANSFER_CONFIRMATION_FORM, VIPER or OTHER. Some values imply something about the prisoner - that a MAPPA review or healthcare assessment exists for them. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_assessment_stage_evidence_source.details IS 'Free text naming the source when OTHER is selected. Null for every named source. [Sensitivity: PERSONAL]';

------------------------------------------------------------------------------------------------
-- csra_assessment_stage_risk_to / _vulnerability - who is at risk, and from what
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_assessment_stage_risk_to IS 'A group the prisoner is judged to pose a risk to, from the "who is this person a risk to?" multi-select on a high-risk stage. One row per selection; NONE is itself a selection, meaning no identified risk to any of these groups.';

COMMENT ON COLUMN csra_assessment_stage_risk_to.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_risk_to.stage_id IS 'Foreign key to csra_assessment_stage. Deleted with the stage. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_risk_to.category IS 'The group at risk - DIFFERENT_ETHNICITY, DIFFERENT_RELIGION, DISABLED, GANG_MEMBERS, SEXUAL_MINORITY, OLD_PEOPLE, SPECIFIC_PERSONS, TRANSGENDER, OTHER or NONE. Records a judgement that the prisoner poses a risk to people of a particular race, religion, sexual orientation, gender identity or disability, which makes it Article 9 data about the prisoner. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage_risk_to.details IS 'Free text expanding on the selection, used mainly for OTHER and SPECIFIC_PERSONS. SPECIFIC_PERSONS means this routinely names third parties. [Sensitivity: SPECIAL-CATEGORY]';

COMMENT ON TABLE csra_assessment_stage_vulnerability IS 'A vulnerable or at-risk group the prisoner is judged to belong to, from the "is this person part of a vulnerable or at-risk group?" multi-select on a high-risk stage. One row per selection; NONE means no identified vulnerabilities. The most directly special category table in the schema - every value is a characteristic of the prisoner rather than a judgement about their behaviour.';

COMMENT ON COLUMN csra_assessment_stage_vulnerability.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_vulnerability.stage_id IS 'Foreign key to csra_assessment_stage. Deleted with the stage. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_assessment_stage_vulnerability.category IS 'The group the prisoner belongs to - DISABLED, SEXUAL_MINORITY, MENTAL_HEALTH, NEURODIVERSITY, OFFENCE_TYPE, OLD_PEOPLE, TRANSGENDER, OTHER or NONE. Health, disability, sexual orientation and gender reassignment are all Article 9 data about the prisoner. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_assessment_stage_vulnerability.details IS 'Free text expanding on the selection, used mainly for OTHER. Expect health and disability detail. [Sensitivity: SPECIAL-CATEGORY]';

------------------------------------------------------------------------------------------------
-- csra_review_nomis - the legacy NOMIS data for a migrated review
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_review_nomis IS 'The extra legacy data for a review migrated or synchronised from NOMIS, held verbatim alongside the core csra_review record rather than mapped onto it. At most one row per review, and none at all for reviews created in DPS. Values are raw NOMIS codes, not the DPS model.';

COMMENT ON COLUMN csra_review_nomis.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.csra_review_id IS 'Foreign key to csra_review - the review this legacy data belongs to. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.score IS 'The numeric score NOMIS calculated for the assessment. A risk score about the prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.status IS 'The review''s status in NOMIS. A status of P (pending) is what makes a migrated rating provisional rather than final. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.calculated_level IS 'The CSRA level NOMIS calculated from the answers, before any reviewer override. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.review_level IS 'The level the reviewer recorded - what NOMIS displays as the approved result, and what the DPS rating is derived from. Do not confuse with approved_level. A risk judgement about the prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.approved_level IS 'NOMIS''s APPROVED_SUP_LEVEL_TYPE. Null on every known CSRA row - NOMIS never populated it, and prison-api does not even map it. Kept for fidelity; read review_level instead. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.committee_code IS 'The NOMIS committee code recorded against the assessment. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.review_committee_code IS 'The NOMIS committee code recorded against the review. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.evaluation_date IS 'The date NOMIS recorded the evaluation. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_review_nomis.evaluation_result_code IS 'The NOMIS evaluation result code - whether the assessment was approved, rejected or referred. A decision about the prisoner''s assessment. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.comment IS 'The free-text assessment comment as recorded in NOMIS. Decades of unstructured narrative about offending, behaviour, health and third parties. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_review_nomis.review_comment IS 'The free-text review comment as recorded in NOMIS. Same caveat as comment. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_review_nomis.review_committee_comment IS 'The free-text committee comment as recorded in NOMIS. Same caveat as comment. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_review_nomis.placement_prison_id IS 'The agency code NOMIS recorded as the placement prison. Indicates where the prisoner was to be held. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.review_placement_prison_id IS 'The agency code NOMIS recorded as the review placement prison. Indicates where the prisoner was to be held. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.review_details IS 'The whole NOMIS question and answer tree, stored as JSONB exactly as supplied and deserialised only when needed. Written as [] rather than null by every write path. The richest single source of special category data in the schema - the legacy questionnaire covers offending, health and vulnerability. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN csra_review_nomis.next_review_date IS 'The next review date NOMIS recorded on this particular review. Distinct from csra_next_review, which holds the one date currently in force for the prisoner - never substitute one for the other. Null for rows migrated before this column existed. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_review_nomis.ingested_at IS 'When this row was last written by migration or sync - this service''s wall clock, not NOMIS''s. The core record''s created_at is NOMIS''s own timestamp, so without this there is no way to tell which migration run produced the current state. Null means the row predates the column. [Sensitivity: NONE]';

------------------------------------------------------------------------------------------------
-- csra_current_rating - the per-prisoner current rating projection
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_current_rating IS 'A prisoner''s single current CSRA rating. One row per prisoner, and the source of truth for "what is this person''s rating today" - it is a stateful projection, not derived on read. It changes only when a rating is saved or when readmission after release resets it, and deliberately persists while a new assessment is merely in progress. A null rating means "No rating".';

COMMENT ON COLUMN csra_current_rating.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.prisoner_number IS 'NOMIS offender number (noms id) of the prisoner this rating is for. One row per prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_current_rating.rating IS 'The current rating - HIGH, HIGH_GENERAL, HIGH_SPECIFIC or STANDARD. Null means "No rating", which is a real state rather than missing data. A risk judgement about the prisoner that determines whether they can share a cell. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_current_rating.provisional IS 'Whether the current rating came from a provisional or interim stage rather than a completed one. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.assessment_type IS 'Which kind of assessment produced the rating, bucketed for display. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.rating_date IS 'The date the current rating was given. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.set_by_review_id IS 'The csra_review that set this rating, for loading the fuller detail. Null when the rating was reset to "No rating" on readmission. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.set_reason IS 'Why the rating was last set - RATING_SAVED, or NO_RATING_ON_READMISSION when a readmission after release cleared it. The latter indicates the prisoner was released and returned to custody. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_current_rating.set_at IS 'When the rating was last set. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_current_rating.set_by IS 'Username that last set the rating, or the system user when a movement event did it. Identifies a member of staff. [Sensitivity: STAFF]';

------------------------------------------------------------------------------------------------
-- csra_next_review - the per-prisoner next review due date
------------------------------------------------------------------------------------------------

COMMENT ON TABLE csra_next_review IS 'The single next-review-due date currently in force for a prisoner. One row per prisoner however many reviews they accumulate, overwritten each time a review sets a new date, and what drives the "high risk prisoners due for review" worklist. The date NOMIS recorded on an individual historic review is on csra_review_nomis instead.';

COMMENT ON COLUMN csra_next_review.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_next_review.prisoner_number IS 'NOMIS offender number (noms id) of the prisoner the date is for. One row per prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_next_review.next_review_date IS 'When the prisoner''s CSRA is next due for review - twelve months on from a high-risk final rating, chosen by the reviewer on the review journey, and cleared when a rating no longer requires one. Its presence therefore indicates a high-risk rating. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN csra_next_review.set_by_review_id IS 'The csra_review that last set this date. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_next_review.updated_at IS 'When the date was last set. [Sensitivity: NONE]';
COMMENT ON COLUMN csra_next_review.updated_by IS 'Username that last set the date, or the system user for migrated and synchronised rows. Identifies a member of staff. [Sensitivity: STAFF]';

------------------------------------------------------------------------------------------------
-- active_agency - prison rollout
------------------------------------------------------------------------------------------------

COMMENT ON TABLE active_agency IS 'One row per prison that has ever been switched on for CSRA in DPS. The switched-on ids are published unauthenticated on the actuator /info payload, which is how the DPS home page decides whether to show the CSRA tile. Switching a prison off flips the flag rather than deleting the row, so deactivation stays auditable and the toggle is idempotent. Note the API itself enforces no rollout check - this drives the front end only.';

COMMENT ON COLUMN active_agency.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.agency_id IS 'Agency (prison) code. Unique - one row per prison. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.active IS 'Whether CSRA is currently switched on for the prison. False means it was switched on at some point and has since been switched off. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.updated_at IS 'When the prison was last switched on or off. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.updated_by IS 'Username of the member of staff who last changed the switch. Identifies a member of staff. [Sensitivity: STAFF]';
