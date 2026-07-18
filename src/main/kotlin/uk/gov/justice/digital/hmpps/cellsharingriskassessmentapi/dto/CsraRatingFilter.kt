package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

/**
 * The fine-grained CSRA rating filter on the prison prisoner list — one value per checkbox the screen
 * shows (High – general, High – specific, High, Standard, No rating). Distinct from the coarse
 * [CsraRatingBucket] (HIGH/STANDARD) used for history summaries.
 */
@Schema(description = "A CSRA rating value for filtering the prison prisoner list; NO_RATING matches prisoners with no current rating")
enum class CsraRatingFilter {
  NO_RATING,
  STANDARD,
  HIGH,
  HIGH_GENERAL,
  HIGH_SPECIFIC,
  ;

  /** The concrete [CsraResult] this filter matches, or null for [NO_RATING]. */
  fun toResult(): CsraResult? = when (this) {
    NO_RATING -> null
    STANDARD -> CsraResult.STANDARD
    HIGH -> CsraResult.HIGH
    HIGH_GENERAL -> CsraResult.HIGH_GENERAL
    HIGH_SPECIFIC -> CsraResult.HIGH_SPECIFIC
  }
}
