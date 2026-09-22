package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

/**
 * The high-risk rating types shown on the "high risk prisoners due for review" screen — the current
 * rating value combined with the stage it came from. Only high-risk ratings map to a value.
 *
 * Day 1 ratings from an initial assessment are provisional; first-sitting ratings from a review are
 * interim. Both may be High – general or High – specific, so the worklist keeps those distinctions in
 * its row labels, filter values and available filter checkboxes.
 */
@Schema(description = "A high-risk CSRA rating type for the due-for-review worklist and its filter")
enum class CsraHighRiskType {
  HIGH,
  HIGH_GENERAL,
  HIGH_GENERAL_PROVISIONAL,
  HIGH_GENERAL_INTERIM,
  HIGH_SPECIFIC,
  HIGH_SPECIFIC_PROVISIONAL,
  HIGH_SPECIFIC_INTERIM,
  ;

  companion object {
    /**
     * The high-risk rating type for a current rating, or null if the rating is not high-risk.
     */
    fun from(rating: CsraResult, ratingStage: CsraRatingStage?): CsraHighRiskType? = when (rating) {
      CsraResult.HIGH -> HIGH
      CsraResult.HIGH_GENERAL -> when (ratingStage) {
        CsraRatingStage.PROVISIONAL -> HIGH_GENERAL_PROVISIONAL
        CsraRatingStage.INTERIM -> HIGH_GENERAL_INTERIM
        else -> HIGH_GENERAL
      }
      CsraResult.HIGH_SPECIFIC -> when (ratingStage) {
        CsraRatingStage.PROVISIONAL -> HIGH_SPECIFIC_PROVISIONAL
        CsraRatingStage.INTERIM -> HIGH_SPECIFIC_INTERIM
        else -> HIGH_SPECIFIC
      }
      CsraResult.STANDARD -> null
    }
  }
}
