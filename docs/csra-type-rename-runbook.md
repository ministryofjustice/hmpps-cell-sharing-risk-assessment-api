# Deploying the CsraType rename (V20)

MAPA-367 renames two values of `csra_review.type`:

| Old | New |
| --- | --- |
| `CSRA_INITIAL_REVIEW` | `CSRA_INITIAL_ASSESSMENT` |
| `REVIEW` | `NOMIS_REVIEW` |

This page exists because the rename is **not** backwards compatible, and the second UPDATE touches a
large share of the migrated NOMIS population. Read it before deploying to preprod or prod.

## Why there has to be a window

`CsraReviewEntity.type` is `@Enumerated(EnumType.STRING)`, so the enum constant name *is* the stored
value. That makes old code and new data mutually unreadable, in both directions:

- Code **before** V20 reading a row that says `NOMIS_REVIEW` fails with `No enum constant CsraType.NOMIS_REVIEW`.
- Code **after** V20 reading a row that still says `REVIEW` fails the same way.

There is no ordering that avoids this, only ways to keep the incompatible window short and quiet. It is
cheap to take now: production has no prison switched on for CSRA (`activeAgencies` on `/info` is empty),
so no user reaches the service.

**`helm rollback` is not a rollback.** Reverting the image after V20 has run leaves new data against an
old enum — every read of a migrated review fails, which is a total outage of the read APIs. Recovery is
either roll-forward with a `V21` reverting the values, or hand-running the reverse UPDATEs *before*
rolling back. See the bottom of this page.

## What dev actually measured

Dev ran Option A on 2026-09-10 (commit `be644d9`). Flyway applied V20 in **19.5 seconds**, one pod, no
restarts, and afterwards:

| type | rows |
| --- | ---: |
| `RATING` | 1,350,413 |
| `NOMIS_REVIEW` | 193,264 |
| `RECEPTION` | 70,539 |
| `FULL` | 16,265 |
| `LOCATE` | 280 |
| `HEALTH` | 28 |
| `CSRA_INITIAL_ASSESSMENT` | 11 |
| `CSRA_REVIEW` | 3 |

Zero rows on either old name, and `type` is now `character varying(40)`. So the 193k-row UPDATE — the one
this whole page is about — costs ~20 seconds against a table of 1.6M rows.

Use that to size prod rather than guessing, but **do not treat it as prod's answer**. Dev holds a full
NOMIS migration, so it is the right shape, but prod's row count and RDS instance class both differ, and
the number that matters is `count(*) WHERE type = 'REVIEW'`, not the table total. Scale from 193k rows
≈ 20s and check the result against the liveness budget below.

## Option A — let Flyway do it (dev, preprod)

Deploy normally. V20 runs at startup. The old pod serves and 500s on migrated reviews for the 30–90
seconds until the new pod is ready.

Fine where the data is small and nobody is watching. **Not** the prod path if the UPDATE runs long: a
migration slower than the liveness budget gets its pod killed mid-flight.

On dev's numbers this is comfortable — 19.5s against a ~6 minute budget. If prod's `REVIEW` count is
within a few multiples of dev's, Option A is defensible there too. Option B stays the recommendation
anyway, because its cost is a scheduled window rather than a risk, and it also closes the poisoned-row
hazard in step 2 that Option A leaves open.

## Option B — planned window with a manual UPDATE (prod)

Chosen for production, because it takes the clock off the big UPDATE entirely.

Both statements in V20 are `WHERE`-filtered, so once the data is renamed by hand the migration matches
nothing and completes instantly. That is the whole point of the filters — don't "tidy" them into
unconditional updates.

### 1. Measure first

Preprod is **not** a volume rehearsal — `values-prod.yaml` has `postgresDatabaseRestore: enabled: false`,
so there is no prod→preprod refresh. Dev's 19.5s (above) is the closest thing to a rehearsal we have. To
do better, restore a prod snapshot into a scratch instance and time it:

```sql
SELECT type, count(*) FROM csra_review GROUP BY type ORDER BY 2 DESC;
SELECT pg_size_pretty(pg_total_relation_size('csra_review'));
\timing on
BEGIN; UPDATE csra_review SET type='NOMIS_REVIEW' WHERE type='REVIEW'; ROLLBACK;
```

Check RDS free storage too. The UPDATE leaves a dead tuple per changed row, so the heap roughly doubles
until autovacuum catches up, and the transaction writes 1–2 GB of WAL.

### 2. Take the app down

```bash
kubectl -n <namespace> scale deployment hmpps-cell-sharing-risk-assessment-api --replicas=0
```

Scaling to zero rather than letting the pods run is what turns a 500 storm into planned downtime. It also
closes the nastier hazard: the old pod still runs the SQS listener, so a NOMIS sync landing *after* the
UPDATE would insert a fresh `type = 'REVIEW'` row the new code can never read — a silently poisoned row
that would not surface until someone opened that prisoner's history weeks later.

**Also pause the NOMIS migrate/sync feed** for the window, so nothing queues up behind a scaled-down app.

### 3. Run the rename

`./gradlew portForwardRDS`, then over `psql`:

```sql
UPDATE csra_review SET type = 'CSRA_INITIAL_ASSESSMENT' WHERE type = 'CSRA_INITIAL_REVIEW';
UPDATE csra_review SET type = 'NOMIS_REVIEW'            WHERE type = 'REVIEW';
```

Run them in one transaction if you prefer, but they are independent and idempotent.

### 4. Deploy and scale back

Deploy the new version. V20 finds nothing to update, applies the `ALTER` and the `COMMENT ON`, and
records itself in `flyway_schema_history` — which is what keeps the schema history honest.

```bash
kubectl -n <namespace> scale deployment hmpps-cell-sharing-risk-assessment-api --replicas=1
```

### 5. Verify

```sql
SELECT type, count(*) FROM csra_review GROUP BY type ORDER BY 2 DESC;
```

**Any surviving `REVIEW` or `CSRA_INITIAL_REVIEW` is a bug**, not a leftover — it means something wrote
after the rename. Check the CSRA DLQ and redrive, then sweep the rows up with a follow-up migration.

Optionally reclaim the dead tuples rather than waiting for autovacuum (it cannot run inside the
migration, which is why this is a manual step):

```sql
VACUUM (ANALYZE) csra_review;
```

## If it goes wrong

**Preferred — roll forward.** Ship a `V21__csra_review_type_rename_revert.sql` with the reverse
`WHERE`-filtered UPDATEs and a Kotlin revert of `CsraType`. Leave the column at `VARCHAR(40)`;
narrowing it forces a full table rewrite and gains nothing.

**Emergency — hand-revert then roll back the image:**

```sql
UPDATE csra_review SET type = 'CSRA_INITIAL_REVIEW' WHERE type = 'CSRA_INITIAL_ASSESSMENT';
UPDATE csra_review SET type = 'REVIEW'              WHERE type = 'NOMIS_REVIEW';
```

Note the trap: `flyway_schema_history` still records V20 as applied, so it will never re-run, and the
next deploy of a fixed image would meet un-renamed data with a renamed enum. Editing data behind
Flyway's back leaves the schema history lying — the reconciliation is always a new migration, and the
`WHERE` filters are what let it converge from either state.
