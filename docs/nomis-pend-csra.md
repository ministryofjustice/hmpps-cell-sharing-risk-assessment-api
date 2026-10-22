# The NOMIS `PEND` level and the status-`P` review

How a migrated NOMIS CSRA resolves to a rating, why almost none of them are provisional, and the two
legacy branches that look load-bearing and never fire.

Implemented in `dto/migration/NomisCsraReviewMappers.kt`. `V8__recalculate_nomis_csra_results.sql`
applied an earlier, wrong version of the rule to already-loaded rows; MAPA-468 corrected the rule, and
those rows are put right by re-migrating (see below).

## Two different things both spelled "pending"

They are unrelated, and conflating them is the easiest mistake to make here.

| | What it is | Where it lives |
| --- | --- | --- |
| `CsraLevel.PEND` | A *level* placeholder meaning "no level chosen yet". Not a rating. | `calculated_level`, `override_level`, `review_level`, `approved_level` |
| `CsraStatus.P` | A *review status* meaning NOMIS itself still holds the review as provisional. | `status` |

A review can carry `PEND` in one level slot and still resolve to a real rating from another. A review
can equally carry a perfectly good level and still be status `P`. Only the second makes a rating
provisional in this service.

## Choosing the level

`resolveNomisLevel` mirrors prison-api's `OffenderAssessment.getClassificationSummary` exactly:

1. The review level (`REVIEW_SUP_LEVEL_TYPE`, what NOMIS displays as the approved result) wins
   outright if it is not `PEND`.
2. Otherwise, where both the override level (`OVERRIDED_SUP_LEVEL_TYPE`) and the calculated level
   (`CALC_SUP_LEVEL_TYPE`) are set, the **stronger** wins (`HI > STANDARD > MED > LOW`), the override
   taking precedence at equal rank.
3. Otherwise the override level, if set — even `PEND`.
4. Otherwise the calculated level, unless it is `PEND`.
5. Otherwise no level at all, and the review carries no rating.

Only the override and the calculated level are ever compared. A calculated level can beat an override,
but never a review level. `approved_level` plays no part (see below).

`PEND` is deliberately absent from `LEVEL_PRIORITY`: it can never win a head-to-head.

The resolved *level* is kept as well as the resolved *result*, because legacy `LOW` and `MED` both
collapse to `CsraResult.STANDARD` and the history screens need to show what NOMIS actually recorded.

> **Corrected by MAPA-468.** Until then the rule ran one column out: it let `approved_level` win
> outright (always null, so never) and compared the *review* level with the calculated level. For
> `calculated = HI, review = STANDARD` NOMIS and the legacy DPS screens show Standard and we showed
> `HIGH`. The override level was also not received separately — the migration service sent
> `overrideLevel ?: calculatedLevel` as `calculatedLevel` — so `override = STANDARD, calculated = HI`
> showed Standard where NOMIS shows High. Rows loaded before the fix have no `override_level` and a
> rating set by the old rule; they are corrected by re-migrating, not by SQL.

## Why a migrated rating is final, not provisional

`NomisCsraOutcome.settled` is **not** "went through the NOMIS approval step".

Approval was a governance step, not a completion step. The level NOMIS displays is the prisoner's
rating whether or not a committee signed it off. Treating unapproved as provisional (the original rule,
corrected by MAPA-253) made *every* migrated review provisional, which is not what provisional means in
this service — here it marks an incomplete Day 1 assessment with work still outstanding, and a legacy
NOMIS review has nothing left to complete.

So a settled outcome lands on `finalResult`. Only status `P` lands on `interimResult` and reads back
through the current-rating projection as provisional.

## The two branches that never fire

Both were found by measuring rather than by reading, and both are worth knowing before you size work
against them.

### `approvedLevel` is always null

`APPROVED_SUP_LEVEL_TYPE` is never populated. prison-api does not even map it, and
hmpps-nomis-prisoner-api annotates it *"actually not used - all null for CSRA top level"*. What NOMIS
displays as the approved result is `REVIEW_SUP_LEVEL_TYPE` — the reviewer's level — which is why
`CsraLegacyReviewDetail.approvedResult` maps from `reviewLevel`.

Measured: **zero rows in 5,107,546** across dev and preprod. Since MAPA-468 `resolveNomisLevel` ignores
it altogether, as prison-api does. It is still stored, and still feeds the approval badge
(`CsraLegacyDetail.approvalStatus`).

### Status `P` never arrives either

Measured in **production on 8 September 2026**, after the full migration completed:

```sql
select interim_result, final_result, count(*) from csra_review
group by final_result, interim_result;
```

| `interim_result` | `final_result` | count |
| --- | --- | --- |
| null | `HIGH` | 1,046,013 |
| null | `STANDARD` | 2,344,644 |
| null | null | 113,089 |

`interim_result` is null across all 3,503,746 rows. Not merely in the rows holding a current rating —
every row. The status-`P` path, the single remaining route by which a migrated review becomes
provisional, has produced nothing.

The 113,089 with neither result are reviews that reached rule 5 — no review level, and a calculated
level that was `PEND` or absent. They are still `COMPLETE`: migrated reviews are historical, never
in-progress, result-less `PEND` rows included.

Correspondingly, `csra_current_rating` in production holds 844,782 `FINAL`, 45,827 null (genuinely "No
rating") and **zero** `PROVISIONAL` or `INTERIM` — every unconfirmed rating in the service is
DPS-authored. In dev, where the write journeys are exercised, the same query returns 533,321 `FINAL`,
4 `PROVISIONAL`, 1 `INTERIM` and 82,193 null.

### Do not delete the status-`P` branch on this evidence

A snapshot showing zero rows is not proof the case cannot occur. NOMIS remains live and a sync arriving
during switch-off could still deliver a status-`P` review. The branch is correct as written and costs
nothing to keep. (The approved-level branch was removed for a different reason: prison-api never used
that column, so keeping it would have been a divergence, not a safeguard.)

What the measurement *is* good for: knowing that **no environment can demonstrate the legacy
provisional case**, so any test or design that depends on observing one in real data is unverifiable.
This matters more than it sounds. `CsraRatingStage` (MAPA-235) distinguishes a review's interim rating
from an assessment's provisional one, and the obvious shortcut — deriving it in the front end as
`provisional && assessmentType === 'REVIEW'` — is wrong precisely because a legacy NOMIS `REVIEW` in
status `P` buckets to `REVIEW` too. That bug would have passed every environment check. The regression
test in `CsraCurrentRatingResourceTest` is the only thing guarding it, which is why it is there.
