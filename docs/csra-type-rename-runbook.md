# Deploying the CsraType rename (V20)

MAPA-367 renames two values of `csra_review.type`:

| Old | New |
| --- | --- |
| `CSRA_INITIAL_REVIEW` | `CSRA_INITIAL_ASSESSMENT` |
| `REVIEW` | `NOMIS_REVIEW` |

**Done: dev, preprod and prod, all on 2026-09-10.** This is now the record of what it cost rather than a
plan, but the reasoning is kept intact — the rename is not backwards compatible, the second UPDATE touches
a large share of the migrated NOMIS population, and the next migration of this shape will need the same
thinking.

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

## What it actually cost

V20 ran with plain Flyway (Option A) in all three environments on 2026-09-10, commit `be644d9`. Every one:
migration succeeded, single pod, no restarts, no `ERROR` lines, no `No enum constant`, DLQ empty.

| | dev | preprod | prod |
| --- | ---: | ---: | ---: |
| `NOMIS_REVIEW` (rows V20 rewrote) | 193,264 | 549,953 | 556,117 |
| `csra_review` total | 1,630,803 | 3,476,764 | 3,503,746 |
| **V20 execution** | **19.5 s** | **56.3 s** | **30.3 s** |
| rows left on an old name | 0 | 0 | 0 |
| `type` column | `varchar(40)` | `varchar(40)` | `varchar(40)` |

The worst case was 56 seconds against a liveness budget of roughly six minutes.

### Two things the numbers contradict

**Preprod is a genuine volume rehearsal.** An earlier draft of this page said it was not, reasoning from
`values-prod.yaml` having `postgresDatabaseRestore: enabled: false` — no prod→preprod refresh, therefore
no prod-like data. Wrong: preprod is within **1.1%** of prod on the rows that matter. It gets there by
running the same NOMIS migration, not by being copied from prod. For anything of this shape, rehearse in
preprod and believe the result.

**Do not size these migrations by row count alone.** Prod rewrote 1.1% *more* rows than preprod in 54% of
the time. Volume did not explain the spread — instance class, cache state and autovacuum timing did, and
we did not pin down which. The useful direction is that preprod ran *slower* than prod, so it errs
conservative, which is the right way round for a rehearsal.

### What prod did not exercise

Prod held **zero** `CSRA_INITIAL_ASSESSMENT` and `CSRA_REVIEW` rows — no prison had been switched on, so
no new-model record had ever been written there (the MAPA-363 rollout gate working as intended).

All three environments read the column back as `varchar(40)`, so the widening is applied everywhere. But
no *data* in prod exercises it yet: the first real assessment write at a live prison is what will. That
widening is the whole reason this migration is not a one-line `UPDATE` — `CSRA_INITIAL_ASSESSMENT` is 23
characters, the column was `varchar(20)`, and with `ddl-auto: none` nothing validates the column against
the entity. Without it the first symptom would have been SQLSTATE 22001 on the next assessment start,
long after a clean deploy.

## Option A — let Flyway do it (dev, preprod)

**This is what was used, in all three environments including prod.** Deploy normally; V20 runs at startup.
The old pod serves and 500s on migrated reviews for the 30-90 seconds until the new pod is ready.

It was the right call because the measurements came back an order of magnitude inside the liveness budget.
The condition under which it stops being the right call is unchanged: a migration slower than that budget
gets its pod killed mid-flight, and this page's Option B exists for that case.

**Preprod was the one environment where this carried real risk**, which is worth remembering because it is
the opposite of what people assume. `activeAgencies` there is `["PVI"]` — a prison switched on and a live
NOMIS feed — while prod had none. So preprod, not prod, was where the read 500s and the poisoned-row
hazard in Option B step 2 were live. Both came back clean, but the step 5 verification is what established
that, and it is not optional anywhere.

## Option B — planned window with a manual UPDATE

**Not used in the end.** It was the plan for production until dev and preprod produced timings that made
it unnecessary. Kept because the reasoning holds for the next migration of this shape, and because the
`WHERE` filters it depends on are still in V20 and must not be "tidied" away.

It takes the clock off the big UPDATE entirely.

Both statements in V20 are `WHERE`-filtered, so once the data is renamed by hand the migration matches
nothing and completes instantly. That is the whole point of the filters — don't "tidy" them into
unconditional updates.

### 1. Measure first

**Preprod is the rehearsal.** An earlier draft of this page said it was not, reasoning from
`values-prod.yaml` having `postgresDatabaseRestore: enabled: false` — no prod→preprod refresh, therefore
no prod-like volume. That inference was wrong, and measuring beats inferring: preprod holds 3,476,764
`csra_review` rows in 774 MB, of which **549,953** are `REVIEW` — 2.8x dev's share, and the same order as
prod. It got there by running the same NOMIS migration, not by being refreshed from prod.

Preprod also has a precedent at this exact scale: **V18 took 25.7 seconds** there, and it is the heavier
operation of the two.

Restore a prod snapshot into a scratch instance only if you want a number for prod specifically:

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
