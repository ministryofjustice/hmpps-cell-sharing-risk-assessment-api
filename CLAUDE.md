# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`hmpps-cell-sharing-risk-assessment-api` is a Kotlin / Spring Boot microservice (an HMPPS DPS service) that owns Cell Sharing Risk Assessment (CSRA) data for prisoners. It is the new service taking ownership of CSRA data currently held in the legacy NOMIS system. Legacy data flows in from NOMIS via `hmpps-prisoner-from-nomis-migration` (one of the additional working directories), new assessments are captured through the service's own write API, everything is persisted in its own Postgres database, and it is read back through the DPS API.

## Build, test, run

Uses Gradle (wrapper) and **JDK 25** (the build pins `jvmToolchain(25)`).

```bash
./gradlew build            # compile, run ktlint, run all tests
./gradlew test             # run all tests
./gradlew ktlintCheck      # lint only
./gradlew ktlintFormat     # auto-fix lint
./gradlew koverHtmlReport  # coverage report (kotlinx-kover)
./gradlew portForwardRDS   # port-forward the cloud-platform RDS instance
```

Run a single test class or method:

```bash
./gradlew test --tests "*CsraReviewResourceTest"
./gradlew test --tests "*CsraMigrationSyncService*.migrate*"
```

### Tests and Docker

Tests need **Docker running**. `PostgresContainer` starts a Testcontainers Postgres unless something is already listening on port 5432 (in which case it reuses it). LocalStack is used for SQS/SNS via the `localstack` profile. There is no separate unit/integration split — all tests run under `./gradlew test`.

### Running the app locally

```bash
docker compose pull && docker compose up                                                    # full stack (app + auth + db + localstack)
docker compose pull && docker compose up --scale hmpps-cell-sharing-risk-assessment-api=0   # deps only; run the app from IntelliJ with the `dev` profile
```

The `dev` profile pulls in `localstack` and has sensible local defaults. Swagger UI: `/swagger-ui/index.html`.

## Architecture

Layered Spring MVC, single package root `uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi`.

### API surfaces

Four controllers in `resource/`, each class-level `@PreAuthorize`d:

| Controller | Base path | Role |
| --- | --- | --- |
| `CsraReviewResource` | `/csra-review` | `ROLE_CSRA_REVIEW__R` |
| `CsraAssessmentResource` | `/csra-review/prisoner/{prisonerNumber}/assessment` | `ROLE_CSRA_REVIEW__RW` |
| `CsraNomisSyncResource` | `/nomis-sync` | `ROLE_PRISONER_CSRA__SYNC__RW` |
| `ActiveAgenciesResource` | `/active-agencies` | `ROLE_PRISONER_CSRA__ADMIN` |

- **`CsraReviewResource`** — all DPS reads: a single review by id, a prisoner's paged/filtered `history` and `current-rating`, and the prison-scoped screens under `/prison/{prisonId}` (`rating-summary`, `prisoners`, `high-risk-due-for-review`, `assessments-in-progress`, `reviews-in-progress`, `recent-arrivals`).
- **`CsraAssessmentResource`** — the new-model write journey: `POST` starts a draft assessment, then `PUT .../{assessmentId}/provisional` (Day 1) and `PUT .../{assessmentId}/final` (Day 2).
- **`CsraNomisSyncResource`** — `migrate/{prisonerNumber}` bulk-loads all of a prisoner's reviews; `sync/{prisonerNumber}` upserts one (201 created / 200 updated based on whether `csraReviewId` is present).
- **`ActiveAgenciesResource`** — prison rollout control: `GET` lists the switched-on prison ids, `GET /all` lists every operational prison with its state for the admin screen, `PUT /{agencyId}` switches one on or off (idempotent). See "Prison rollout" below.

`CellSharingRiskAssessmentApiExceptionHandler` is the global `@RestControllerAdvice`; throw the typed exceptions (`CsraReviewNotFoundException`, `MandatoryHighRiskGeneralException`, `CsraAssessmentInProgressException`) rather than building responses by hand. Each maps to a stable numeric `ErrorCode` — **existing `ErrorCode` values must never change**.

### Services

- **`CsraAssessmentService`** — the two-stage initial assessment write journey. Start rejects a prisoner who already has an unrated, non-archived review. Each stage upserts a `CsraAssessmentStageEntity`, writes the rating onto the review (`interimResult` for PROVISIONAL, `finalResult` + `status = COMPLETE` for FINAL), refreshes the current-rating projection, sets/clears the next review date, and raises a domain event + audit (`CSRA_CREATED` for the review's first rating, `CSRA_AMENDED` after).
- **`CsraReviewService`** — all reads, including the prison-scoped screens. Read-only transaction.
- **`CsraMigrationSyncService`** — NOMIS ingest (see below).
- **`CsraCurrentRatingService`** — maintains the `csra_current_rating` projection.
- **`CsraMovementService`** — reacts to prisoner movements.
- **`ActiveAgenciesService`** — which prisons have CSRA switched on. Deliberately **uncached** (see "Prison rollout").
- **`SnsService` / `AuditService` / `EventPublishAndAuditService`** — outbound events and audit.

### Persistence model (`jpa/`)

- **`CsraReviewEntity`** (`csra_review`) — the core record, holding **only data common to both the new assessment journey and migrated NOMIS reviews**: prisoner, prison, dates, `CsraType`, `interimResult`/`finalResult` (`CsraResult`), lifecycle `status`, closure fields, audit stamps. Don't add legacy-only or journey-only fields here.
- **`CsraReviewNomisEntity`** (`csra_review_nomis`) — 1:0..1 adjacent record holding the legacy NOMIS-only data **verbatim** (raw levels, score, status, committee/approval data, comments, placement prisons) plus the whole `reviewDetails` question/answer tree as a JSONB blob via `@JdbcTypeCode(SqlTypes.JSON)`.
- **`CsraAssessmentStageEntity`** (`csra_assessment_stage`) — one row per `CsraAssessmentStage` (PROVISIONAL/FINAL) of a new-model assessment, carrying the rich capture data (evidence-source flags, seven offence flags, conversation/vulnerability answers, healthcare, comment) plus child collections `CsraAssessmentStageRiskToEntity` / `CsraAssessmentStageVulnerabilityEntity`.
- **`CsraNextReviewEntity`** (`csra_next_review`) — one row per prisoner holding their single scheduled next review date (12 months on from a high-risk final rating; cleared otherwise), stamped with the review that set it.
- **`CsraCurrentRatingEntity`** (`csra_current_rating`) — the per-prisoner current-rating projection.
- **`ActiveAgencyEntity`** (`active_agency`) — one row per prison that has ever been switched on for CSRA, with `active`, `updatedAt` and `updatedBy`. Switching off flips the flag rather than deleting the row, so deactivation stays auditable and the toggle idempotent.

IDs are time-ordered **UUID v7** generated by `jpa/helper/UuidV7Generator` (`@GeneratedUuidV7`) for index-friendly inserts. Complex history filtering lives in `CsraReviewSpecifications`.

### Review lifecycle and the current-rating projection

`CsraReviewStatus` is the review lifecycle: `IN_PROGRESS` → `COMPLETE`, or `CLOSED` / `ARCHIVED` when a movement interrupts it. `CLOSED` means it had a provisional rating that still stands; `ARCHIVED` means it had no rating and is hidden from the service entirely.

A prisoner's current CSRA rating is **not** derived on read — it is the stateful `csra_current_rating` projection, and `CsraCurrentRatingService` is the only thing that writes it:

- `refreshFromReviews(prisonerNumber)` recomputes it from the latest rated, non-archived review. Call it after **any** path that saves a rating (assessment journey, migrate, sync).
- `resetToNoRating(...)` clears it on readmission after release (rule R-01).

Merely starting a new assessment leaves the projection alone, so the prior rating persists while the new one is in progress. `CsraReviewService.getCurrentRating` reads the projection first and only falls back to an in-progress review (status `IN_PROGRESS`) or "No rating".

### Prison rollout

CSRA is rolled out prison by prison, and `active_agency` is the switch. `ActiveAgenciesInfo` (an actuator `InfoContributor`) publishes the switched-on ids as `activeAgencies` on the **public** `/info`, in the standard HMPPS shape — that is what lets the DPS home page decide whether to show the CSRA tile without holding a privileged token. The UI's admin screen drives the `/active-agencies` endpoints.

Two things about this are easy to get wrong:

- **`ActiveAgenciesService` must not be cached.** The service runs four replicas, so a per-pod cache makes an admin's on/off toggle appear to flip-flop as successive polls of `/info` land on different pods. The table is tiny and indexed; read it live.
- **The API deliberately gates nothing.** Rollout drives the DPS home page tile (via `/info`) and the UI's own journeys; the API itself enforces no rollout check on any path, read or write. That is a decision, not an omission (MAPA-246): CSRA data must be updatable through the API regardless of a prison's rollout state, for data fixes, migration catch-up and support work. Don't add rollout checks to resources or services. Reading is likewise open to any user with the prisoner in their caseload — the worklists are read-only and accurate either way.

The other half of rollout is NOMIS-side and lives in the UI, not here: the NOMIS modules `OCDNOQUE` (Offender Assessment Questionnaires) and `OIDCAPPR` (Classification Approval) are switched together between normal/warning/blocked via prison-api splash screens, giving DPS/NOMIS mutual exclusivity.

### Domain events & audit

Two independent outbound channels, both via `hmpps-sqs-spring-boot-starter` (`HmppsQueueService`):

- **Domain events** → SNS `domainevents` topic. `SnsService.publishDomainEvent` wraps the payload in an `HMPPSDomainEvent` (version `1.0`, `occurredAt` formatted as ISO offset in `Europe/London`). Event types are the `CSRADomainEventType` enum (`cell.sharing.risk.assessment.created` / `.amended`); each value also carries the matching `AuditType`.
- **Audit** → SQS `audit` queue. `AuditService.sendMessage` builds an `AuditEvent` (`what` = audit type, `who` = `HmppsAuthenticationHolder.username` falling back to `SYSTEM_USERNAME`, `service` = app name, `details` = JSON of the supplied object) and sends it with the queue's SQS client, plus an App Insights telemetry event.

`EventPublishAndAuditService.publishEvent` ties the two together, and three behaviours in it are load-bearing:

1. **Deferred until commit** — it registers an `afterCommit` synchronisation, so a rolled-back write never announces itself.
2. **Unrated CSRAs are audited but never published** — a review with neither an interim nor a final result is still a draft; it must be able to be started, amended and cancelled without any consumer seeing it. Suppression is recorded as `csra-event-suppressed-no-rating` telemetry.
3. **`InformationSource` stamps the origin** (`DPS` or `NOMIS`). The migration/sync path publishes with `NOMIS` so the sync service recognises the echo of its own write and doesn't loop NOMIS → DPS → NOMIS.

### Inbound events

`PrisonerMovementListener` consumes the `csra` SQS queue (subscribed to the shared domain-events topic, filtered to `prison-offender-events.prisoner.received`). Only *received* matters — nothing happens at release; in-progress work is tidied up on the next admission, and the event's `reason` distinguishes a readmission after release (R-01, resets the rating to No rating) from a transfer (R-02, rating retained). Court/TAP returns are ignored here — note this is **not** the same rule as the recent-arrivals screen, which does count them (see below): a court return brings someone back onto the wing and may need a CSRA, but it doesn't end in-progress work the way an admission or transfer does. `CsraMovementService` closes reviews that already have a rating and archives those that don't; both are naturally idempotent on redelivery. The listener can be disabled with `csra.process-movement-events=false`.

### Outbound HTTP clients (`client/`)

- **`PrisonRegisterClient`** — unauthenticated public reference data: prison names, plus `getActivePrisonIds()` (the prisons prison-register still holds as operational) so the rollout admin screen doesn't offer closed ones. Cached in-process with a short TTL, and **only a successful fetch is cached** so a transient error can't poison the list for the whole TTL; it **degrades gracefully** — a failure serves the last good cache or nothing, and names fall back to the prison id.
- **`PrisonerSearchClient`** — authenticated (OAuth2 client-credentials). Supplies the prison roll and prisoner names. **Failures propagate** — a partial roll would silently corrupt the counts. Note the `Content-Type` default header in `WebClientConfiguration` is required: prisoner-search 500s without it, even on a GET.
- **`PrisonApiClient`** — authenticated; external movements for the recent-arrivals screen. The system client also needs `ROLE_ESTABLISHMENT_ROLL`.

Health indicators exist for each (`health/`), and each has a WireMock server + JUnit extension in `integration/wiremock/`.

### Key domain modelling decisions

- Legacy-only NOMIS detail belongs on `CsraReviewNomisEntity`, not the core `CsraReviewEntity`.
- `NomisCsraReview` field names **must match the producer's `CsraReviewDto` exactly** so the JSON binds — when changing them, cross-check `hmpps-prisoner-from-nomis-migration`.
- **NOMIS rating resolution** (`NomisCsraReviewMappers.resolveNomisLevel`) mirrors how NOMIS itself derives the displayed CSRA (prison-api `OffenderAssessment.getClassificationSummary`): an approved non-`PEND` level wins; otherwise the *stronger* of the reviewer's and calculated levels (`HI > STANDARD > MED > LOW`), reviewer winning ties; otherwise the reviewer's level; otherwise the calculated level unless `PEND`. `V8__recalculate_nomis_csra_results.sql` re-derives this in SQL for already-loaded rows; keep the two in step. See `docs/nomis-pend-csra.md`.
- **A NOMIS rating is *final*, not provisional** (MAPA-253). Approval was a governance step, not a completion step, and in practice no NOMIS review carries an approved level at all — the old "unapproved ⇒ `interimResult`" rule therefore made 100% of migrated reviews provisional. The only provisional case is a review NOMIS itself still holds in status `P` (`NomisCsraOutcome.settled`).
- NOMIS has no "high risk specific" level, so `HIGH_SPECIFIC` is never produced by migration, and legacy `HIGH` is deliberately kept distinct from new-model `HIGH_GENERAL`.
- Legacy `LOW` and `MED` collapse to `CsraResult.STANDARD` and stay that way — they have been unused in NOMIS for years, so they are display-only history. The CSRA history endpoint returns the raw level (and the approval detail) in a nullable `legacy` block on each row, whose presence is also what identifies a row as NOMIS-sourced. The rule: add to `CsraResult` when a distinction changes what the service *does*; expose the raw record when it only changes what a screen *shows*.
- `CsraArrivalType.fromMovement` decides what counts as an arrival: `ADM`, `TRN`, `CRT` and `TAP` all do, giving the screen's four filter types. `ADM` is refined by its reason code as well as its type, because NOMIS normally records an inter-prison transfer in as an `ADM` with reason `INT` rather than a `TRN` — mapping on type alone would report those as new admissions. `movementReasonCode` was added to prison-api's `/api/movements/{agencyId}/in` for exactly this. The endpoint only ever returns movements *into* the prison, so there is no direction to filter on.
- Recent arrivals returns one section per calendar day in the window, including days nobody arrived on, and one row per prisoner **per day** — someone who arrived on two days appears under both. Names, date of birth and current location come from the prisoner-search roll, not from the movement, so the location shown is where the person is now.
- Selecting a mandatory high-risk offence flag (murder/manslaughter, assisting suicide, sexual assault) forces the rating to `HIGH_GENERAL` — enforced in `CsraAssessmentService.validateMandatoryHigh`.
- The prison-scoped prisoner lists join, filter, sort and page **in memory** on purpose: names live in prisoner-search and "No rating" prisoners have no `csra_review` row, so none of it can be pushed to the database or to prisoner-search. Roll lookups are chunked (`RATING_COUNT_BATCH_SIZE`) to keep `IN (...)` lists sane.

## Data & migrations

Postgres with **Flyway** (`spring.flyway.enabled=true`, `ddl-auto: none`, `generate-ddl: false` — schema is never auto-generated). Add schema changes as new `src/main/resources/db/migration/V{n}__*.sql` files; never edit an applied migration.

## Conventions

- ktlint is enforced in the build (HMPPS `dps-gradle-spring-boot` plugin config) — run `./gradlew ktlintFormat` before considering work done.
- Roles are checked with class- or method-level `@PreAuthorize("hasRole('ROLE_...')")`; new endpoints must declare their role.
- `ResourceSecurityTest` and `OpenApiDocsTest` assert that every endpoint is secured and documented — keep new endpoints covered by `@PreAuthorize` and Swagger annotations or these fail.
- Entity → DTO mapping is done with extension functions living beside the DTO (`CsraReview.toDto()`, `CsraType.toAssessmentBucket()`, `CsraRatingBucket.toResults()`).
- Time zone is fixed to `Europe/London` (Jackson + event formatting). Inject `Clock` rather than calling `now()` directly — `TestBase` pins a fixed clock at `2023-12-05T12:34:56Z`.

## Testing

- `TestBase` wires the Testcontainers Postgres and the fixed clock; `IntegrationTestBase` adds `WebTestClient`, `JwtAuthorisationHelper` (`setAuthorisation(roles = ...)`) and the four WireMock extensions; `SqsIntegrationTestBase` adds LocalStack plus helpers for the queues.
- The `test` profile defines the `audit` queue, a `test` queue subscribed to `domainevents` (filtered on the `cell.sharing.risk.assessment` event-type prefix) and the inbound `csra` queue (filtered to `prison-offender-events.prisoner.received`). Assert outbound events with `getDomainEvents()`; drive the listener with `publishDomainEvent(...)` then `awaitCsraQueueDrained()`.
- When seeding reviews directly through a repository, call `refreshCurrentRating(prisonerNumber)` — otherwise the current-rating projection stays empty and prison-scoped reads see "No rating".

## Notes

- README.md covers tech stack, endpoints and local running; it retains some inherited HMPPS template content around namespaces and renaming.
- `docs/` holds design notes for the persistence work (`csra-persistence-step2.md` — core review, `csra-persistence-new-model.md` — the NOMIS adjacent table and new model, `nomis-pend-csra.md` — the `PEND` level).
