# The NOMIS `PEND` level and the status-`P` review

How a migrated NOMIS CSRA resolves to a rating, why almost none of them are provisional, and the two
legacy branches that look load-bearing and never fire.

Implemented in `dto/migration/NomisCsraReviewMappers.kt` and, for already-loaded rows, in
`V8__recalculate_nomis_csra_results.sql`. **The two must stay in step** — if you change one, change the
other.

## Two different things both spelled "pending"

They are unrelated, and conflating them is the easiest mistake to make here.

| | What it is | Where it lives |
| --- | --- | --- |
| `CsraLevel.PEND` | A *level* placeholder meaning "no level chosen yet". Not a rating. | `calculated_level`, `review_level`, `approved_level` |
| `CsraStatus.P` | A *review status* meaning NOMIS itself still holds the review as provisional. | `status` |

A review can carry `PEND` in one level slot and still resolve to a real rating from another. A review
can equally carry a perfectly good level and still be status `P`. Only the second makes a rating
provisional in this service.

## Choosing the level

`resolveNomisLevel` mirrors prison-api's `OffenderAssessment.getClassificationSummary`:

1. An approved level that is not `PEND` wins outright.
2. Otherwise, where both the reviewer's and the calculated level are set, the **stronger** wins
   (`HI > STANDARD > MED > LOW`), the reviewer's taking precedence at equal rank.
3. Otherwise the reviewer's level, if set — this is where NOMIS displays a bare "PEND".
4. Otherwise the calculated level, unless it is `PEND`.
5. Otherwise no level at all, and the review carries no rating.

`PEND` is deliberately absent from `LEVEL_PRIORITY`: it can never win a head-to-head.

The resolved *level* is kept as well as the resolved *result*, because legacy `LOW` and `MED` both
collapse to `CsraResult.STANDARD` and the history screens need to show what NOMIS actually recorded.

> **Known divergence.** Rule 2 takes the stronger of reviewer and calculated; NOMIS takes the
> reviewer's level outright. For `calculated = HI, review = STANDARD` the legacy DPS screen shows
> Standard and we show `HIGH`. Unresolved — measure before relying on it either way.

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

Measured: **zero rows in 5,107,546** across dev and preprod. Rule 1 of `resolveNomisLevel` is therefore
dead in practice.

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

The 113,089 with neither result are reviews that reached rule 5 — no reviewer level, and a calculated
level that was `PEND` or absent. They are still `COMPLETE`: migrated reviews are historical, never
in-progress, result-less `PEND` rows included.

Correspondingly, `csra_current_rating` in production holds 844,782 `FINAL`, 45,827 null (genuinely "No
rating") and **zero** `PROVISIONAL` or `INTERIM` — every unconfirmed rating in the service is
DPS-authored. In dev, where the write journeys are exercised, the same query returns 533,321 `FINAL`,
4 `PROVISIONAL`, 1 `INTERIM` and 82,193 null.

### Do not delete either branch on this evidence

A snapshot showing zero rows is not proof the case cannot occur. NOMIS remains live and a sync arriving
during switch-off could still deliver a status-`P` review or, less plausibly, an approved level. Both
branches are correct as written and cost nothing to keep.

What the measurement *is* good for: knowing that **no environment can demonstrate the legacy
provisional case**, so any test or design that depends on observing one in real data is unverifiable.
This matters more than it sounds. `CsraRatingStage` (MAPA-235) distinguishes a review's interim rating
from an assessment's provisional one, and the obvious shortcut — deriving it in the front end as
`provisional && assessmentType === 'REVIEW'` — is wrong precisely because a legacy NOMIS `REVIEW` in
status `P` buckets to `REVIEW` too. That bug would have passed every environment check. The regression
test in `CsraCurrentRatingResourceTest` is the only thing guarding it, which is why it is there.
