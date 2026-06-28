MAPA-141 CSRA – Step 3: Persist all NOMIS sync data, and the new-model storage direction

## Why

Step 2 (MAPA-51, `csra-persistence-step2.md`) persisted only the **core** CSRA fields common to both
the new assessment journey and migrated NOMIS reviews (`csra_review`). Everything else the NOMIS sync
sends in `NomisCsraReview` — score, status, the raw calculated/review/approved levels, committee and
approval data, comments, placement prisons, and the whole `reviewDetails` question/answer tree — was
**dropped** by the mappers and lost on ingest.

This step stores that additional data so nothing the sync sends is discarded, and records the
recommended approach for the richer new (DPS) CSRA model so the next ticket can build it.

## What changed (implemented)

### Database

- New Flyway migration `V2__csra_review_nomis.sql` creating `csra_review_nomis`, with its own UUID
  primary key and a unique, non-null FK to `csra_review(id)` (`ON DELETE CASCADE`) — a **1:0..1**
  relationship to the core review.

### JPA layer (`jpa/`)

- **`CsraReviewNomisEntity`** (`csra_review_nomis`) — holds only the NOMIS fields not already on the
  core record, keeping the **raw NOMIS values verbatim** (no translation):
  - `score`, `status`, `calculatedLevel`, `reviewLevel`, `approvedLevel`, `committeeCode`,
    `reviewCommitteeCode`, `evaluationDate`, `evaluationResultCode`, `comment`, `reviewComment`,
    `reviewCommitteeComment`, `placementPrisonId`, `reviewPlacementPrisonId`.
  - `reviewDetails` — the section/question/response tree stored as a single **JSONB** blob via
    Hibernate 6's native `@JdbcTypeCode(SqlTypes.JSON)` (no extra dependency), deserialized only when
    needed. The blob reuses the existing `CsraReviewDetailDto`/`CsraQuestionDto`/`CsraResponseDto`
    shapes from `NomisCsraReview`.
  - Nullable `@OneToOne` to `CsraReviewEntity`; the core entity is left **unchanged**.
  - `bookingId` and `nomisSequence` are deliberately **not** stored (not needed by DPS).
- **`CsraReviewNomisRepository`** — `findByCsraReviewId(id)` for reading/updating the adjacent record.

### Mapping (`dto/migration/NomisCsraReviewMappers.kt`)

- `toNomisEntity(core)` builds the adjacent record for a freshly mapped core review; an
  `updateFromNomis(review)` overload refreshes it on sync updates. Core mapping is unchanged.

### Service (`service/CsraMigrationSyncService.kt`)

- `migrate` and `sync` now save/refresh the adjacent `csra_review_nomis` row in the same transaction
  as the core row. No API/response-shape change; no new endpoint (the additional data is read on
  demand), so `ResourceSecurityTest` / `OpenApiDocsTest` are unaffected.

## Read & query design (why this avoids unions)

Old (NOMIS-migrated) and new (DPS) reviews are **both rows in the single `csra_review` table** — not
separate tables. So loading a prisoner's records with paging, sorting, date ranges and prison
filtering is single-table SQL (`WHERE … ORDER BY … LIMIT/OFFSET`, or keyset on the time-ordered v7
`id`), with **no union and no join**. Every list filter/sort dimension already lives on `csra_review`
(`prisoner_number`, `assessment_date`/`next_review_date`/`final_result_date`, `prison_id`, `type`,
`final_result`).

The 1:0..1 side tables (`csra_review_nomis`, and the future new-model table) are `LEFT JOIN`ed **only
when drilling into a single review's detail**, never for list queries.

Caveats:
- `prison_id` is nullable for some legacy rows (NOMIS doesn't always send it) — a prison filter won't
  match those.
- A genuinely legacy-only dimension (e.g. NOMIS `status`) lives in the side table; filtering on it is
  a simple `LEFT JOIN`, still not a union. If such a field needs routine cross-review filtering,
  **promote it onto `csra_review`** rather than querying the side table.

When the list/search endpoint is built, add supporting indexes for the chosen filters/sorts (e.g.
composite `(prisoner_number, assessment_date)`, plus `prison_id` / `type` as needed) and use Spring
Data paging (`Pageable` / `Page<CsraReviewEntity>`).

## New (DPS) CSRA model — recommended approach (design only, not built)

The new journey captures a series of questions/answers in the front end, similar to
`hmpps-incident-reporting`. Recommendation: a **hybrid** model, not the two extremes.

- **Reuse `csra_review`** for the common, queryable fields it already holds (type, interim/final
  result + dates, next review date, audit). `CsraType` already distinguishes new-model values
  (`CSRA_INITIAL_REVIEW`, `CSRA_REVIEW`) from legacy ones via a `legacy` flag — finish wiring that
  flag as a stored/usable property.
- **New child table `csra_assessment_answers`** (FK `csra_review_id`, 1:0..1) with an `answers`
  **JSONB** document holding the full captured questionnaire, plus **promoted typed columns only**
  for the handful of fields that must be queried / reported on / drive logic.

Why not the alternatives:
- **Fully normalized (incident-reporting style):** `hmpps-incident-reporting` uses normalized
  question/response tables (40+ migrations, heavy churn as question sets evolve). CSRA's new question
  set is still being designed, so JSONB avoids that migration churn and is consistent with how the
  legacy Q&A blob is stored; promote-on-demand keeps the fields we actually query first-class.
- **Pure JSONB document:** we still want result/type/dates/audit as real columns for the read API,
  indexing and reporting — the existing core row already provides these.

## Follow-ups

- Finalize the new-model question-set contract with the front end and decide the promoted columns.
- Build the list/search read endpoint with paging/sorting/filtering and its supporting indexes.
- Wire outbound domain events for create/amend — the `EventPublishAndAuditService` / `SnsService`
  plumbing exists but `publishEvent` only audits today; decide whether migration/sync should raise
  events with `InformationSource.NOMIS`.
- Optional: expose the additional NOMIS detail (including the Q&A blob) via a read endpoint if/when a
  consumer needs it.
