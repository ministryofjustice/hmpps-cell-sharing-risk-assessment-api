# hmpps-cell-sharing-risk-assessment-api

[![Ministry of Justice Repository Compliance Badge](https://github-community.service.justice.gov.uk/repository-standards/api/hmpps-cell-sharing-risk-assessment-api/badge?style=flat)](https://github-community.service.justice.gov.uk/repository-standards/hmpps-cell-sharing-risk-assessment-api)
[![Docker Repository on ghcr](https://img.shields.io/badge/ghcr.io-repository-2496ED.svg?logo=docker)](https://ghcr.io/ministryofjustice/hmpps-cell-sharing-risk-assessment-api)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://cell-sharing-risk-assessment-api-dev.hmpps.service.justice.gov.uk/swagger-ui/index.html)

The API that owns Cell Sharing Risk Assessment (CSRA) data for prisoners within Digital Prison Services (DPS).

This service is taking ownership of CSRA data that currently lives in the legacy NOMIS system. Existing data is migrated and kept in sync from NOMIS via [hmpps-prisoner-from-nomis-migration](https://github.com/ministryofjustice/hmpps-prisoner-from-nomis-migration), persisted in this service's own PostgreSQL database, and exposed back to DPS through a read API.

This project is community managed by the mojdt `#kotlin-dev` slack channel. Please raise any questions or queries there. Contributions welcome!

Our security policy is located [here](https://github.com/ministryofjustice/hmpps-cell-sharing-risk-assessment-api/security/policy).

## Tech stack

- Kotlin / Spring Boot (Spring MVC), built with Gradle
- JDK 25
- PostgreSQL with Flyway migrations
- AWS SNS (domain events) and SQS (audit) via the HMPPS SQS Spring Boot starter
- HMPPS Auth (OAuth2) for authentication and role-based authorisation
- Application Insights / OpenTelemetry for monitoring

## API documentation

OpenAPI / Swagger UI is available at `/swagger-ui/index.html` on a running instance (e.g. the [dev environment](https://cell-sharing-risk-assessment-api-dev.hmpps.service.justice.gov.uk/swagger-ui/index.html)).

The API has four surfaces, each guarded by its own role.

**Reads** — `ROLE_CSRA_REVIEW__R`

| Path | Purpose |
| --- | --- |
| `GET /csra-review/{id}` | A single CSRA review |
| `GET /csra-review/prisoner/{prisonerNumber}/history` | A prisoner's paged, filterable CSRA history |
| `GET /csra-review/prisoner/{prisonerNumber}/current-rating` | A prisoner's current rating, and any in-progress work |
| `GET /csra-review/prison/{prisonId}/rating-summary` | Rating counts across the prison's roll |
| `GET /csra-review/prison/{prisonId}/prisoners` | The roll with each prisoner's rating |
| `GET /csra-review/prison/{prisonId}/high-risk-due-for-review` | High-risk prisoners with a review due |
| `GET /csra-review/prison/{prisonId}/assessments-in-progress` | Initial assessments started but not completed |
| `GET /csra-review/prison/{prisonId}/reviews-in-progress` | Reviews started but not completed |
| `GET /csra-review/prison/{prisonId}/recent-arrivals` | Arrivals by day, for assessment triage |

**Assessment writes** — `ROLE_CSRA_REVIEW__RW`

| Path | Purpose |
| --- | --- |
| `POST /csra-review/prisoner/{prisonerNumber}/assessment` | Start a draft assessment. Requires `{"prisonId": "LEI"}` — the prison is what puts the draft on that prison's worklist |
| `PUT /csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/provisional` | Submit the provisional (Day 1) stage |
| `PUT /csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/final` | Submit the final (Day 2) stage |

**NOMIS sync** — `ROLE_PRISONER_CSRA__SYNC__RW`

| Path | Purpose |
| --- | --- |
| `POST /nomis-sync/migrate/{prisonerNumber}` | Bulk migrate all of a prisoner's CSRA reviews from NOMIS |
| `POST /nomis-sync/sync/{prisonerNumber}` | Upsert a single CSRA review changed in NOMIS (201 created / 200 updated) |

**Prison rollout admin** — `ROLE_PRISONER_CSRA__ADMIN`

| Path | Purpose |
| --- | --- |
| `GET /active-agencies` | The prison ids CSRA is switched on for |
| `GET /active-agencies/all` | Every operational prison with its on/off state |
| `PUT /active-agencies/{agencyId}` | Switch a prison on or off (idempotent) |

The switched-on prison ids are also published unauthenticated as `activeAgencies` on `/info`, which is how
the DPS home page decides whether to show the CSRA tile.

## Building and testing

The project uses the Gradle wrapper, so a local Gradle install is not required.

```bash
./gradlew build            # compile, run ktlint and all tests
./gradlew test             # run all tests
./gradlew ktlintFormat     # auto-fix lint issues
./gradlew koverHtmlReport  # generate the test coverage report
```

Run a single test class or method:

```bash
./gradlew test --tests "*CsraReviewResourceTest"
```

### Docker is required for tests

The test suite uses [Testcontainers](https://www.testcontainers.org/) to start a PostgreSQL database and [LocalStack](https://localstack.cloud/) for SNS/SQS, so **Docker must be running** to execute the tests. If a PostgreSQL instance is already listening on port 5432 (e.g. from `docker compose`), the tests will reuse it instead of starting a container.

## Running the application locally

The application has a `dev` Spring profile with sensible defaults for local running (these are not used in Kubernetes, where values come from the Helm configuration).

A `docker-compose.yml` is provided to run the service together with its dependencies — HMPPS Auth, PostgreSQL and LocalStack:

```bash
docker compose pull && docker compose up
```

To run only the dependencies and start the application yourself (e.g. from IntelliJ with the `dev` profile active):

```bash
docker compose pull && docker compose up --scale hmpps-cell-sharing-risk-assessment-api=0
```

Once running, the service is available on `http://localhost:8080` (health at `/health`, Swagger at `/swagger-ui/index.html`).

## Architecture overview

Standard layered Spring service under `uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi`:

- `resource/` — REST controllers (`CsraReviewResource`, `CsraNomisSyncResource`) and the global exception handler.
- `service/` — business logic (`CsraReviewService`, `CsraMigrationSyncService`) plus outbound domain-event (`SnsService`) and audit (`AuditService`) publishing.
- `jpa/` — the `CsraReviewEntity` (`csra_review` table), enums and repository. Primary keys are time-ordered UUID v7 values for index-friendly inserts.
- `dto/` — the DPS API model, with `dto/migration/` holding the legacy NOMIS shapes and the mappers that translate them onto the core entity.

The core record holds only the data common to both the new DPS assessment journey and migrated NOMIS reviews; richer legacy-only NOMIS detail is intentionally not stored on this entity.

## Database migrations

Schema is managed entirely by Flyway (no Hibernate auto-DDL). Add changes as new versioned scripts under `src/main/resources/db/migration/` (`V{n}__description.sql`) and never edit a migration that has already been applied.

## Deployment

The service is deployed to the MOJ Cloud Platform via Helm. Charts and per-environment values live in `helm_deploy/` (`values-dev.yaml`, `values-preprod.yaml`, `values-prod.yaml`), and deployment runs through the GitHub Actions workflows in `.github/workflows/`. The product ID is `DPS126`.

## Common HMPPS Kotlin patterns

This service follows the shared HMPPS Kotlin conventions. Documentation for these patterns is in the [HMPPS tech docs](https://tech-docs.hmpps.service.justice.gov.uk/common-kotlin-patterns/). If that documentation is incorrect or needs improving please report it to [#ask-prisons-digital-sre](https://moj.enterprise.slack.com/archives/C06MWP0UKDE) or [raise a PR](https://github.com/ministryofjustice/hmpps-tech-docs).
