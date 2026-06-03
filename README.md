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

The API has two surfaces, each guarded by its own role:

| Path | Purpose | Required role |
| --- | --- | --- |
| `GET /csra-review/{id}` | Read a CSRA review (DPS) | `ROLE_CSRA_REVIEW__R` |
| `POST /nomis-sync/migrate/{prisonerNumber}` | Bulk migrate all of a prisoner's CSRA reviews from NOMIS | `ROLE_PRISONER_CSRA__SYNC__RW` |
| `POST /nomis-sync/sync/{prisonerNumber}` | Upsert a single CSRA review changed in NOMIS (201 created / 200 updated) | `ROLE_PRISONER_CSRA__SYNC__RW` |

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
