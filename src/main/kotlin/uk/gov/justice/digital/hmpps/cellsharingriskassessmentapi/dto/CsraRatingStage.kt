package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType

/**
 * Which stage of its journey the current rating came from — the discriminator [CsraCurrentRating.provisional]
 * cannot supply on its own.
 *
 * Both journeys write their unfinished rating to the same `interim_result` column
 * ([CsraAssessmentStage.PROVISIONAL] and [CsraAssessmentStage.INTERIM] alike), so `provisional = true`
 * says a rating is not yet confirmed but not what the user was told it was called. The screens name the
 * two differently — "HIGH RISK GENERAL (PROVISIONAL)" against "HIGH RISK GENERAL (INTERIM)" — and sort
 * them differently, so the distinction has to reach the front end.
 *
 * - FINAL: a confirmed rating. Equivalent to `provisional = false`.
 * - PROVISIONAL: an initial assessment's Day 1 rating.
 * - INTERIM: a review's first-sitting rating, issued before the multidisciplinary team has met.
 *
 * Absent when the prisoner has no rating at all.
 */
@Schema(description = "Which stage of its journey the current rating came from")
enum class CsraRatingStage {
  FINAL,
  PROVISIONAL,
  INTERIM,
}

/**
 * The stage a rating came from, or null when there is no rating.
 *
 * Keyed off the full [CsraType] rather than [CsraAssessmentTypeBucket], which is not precise enough:
 * the bucket collapses the legacy NOMIS `REVIEW` into the same value as the new-model `CSRA_REVIEW`, and
 * a migrated review NOMIS still holds in provisional status carries an interim result. Only a DPS review
 * produces an interim rating; everything else that is unconfirmed is provisional.
 */
fun ratingStageFor(type: CsraType, finalResult: CsraResult?, interimResult: CsraResult?): CsraRatingStage? = when {
  finalResult != null -> CsraRatingStage.FINAL
  interimResult == null -> null
  type == CsraType.CSRA_REVIEW -> CsraRatingStage.INTERIM
  else -> CsraRatingStage.PROVISIONAL
}
