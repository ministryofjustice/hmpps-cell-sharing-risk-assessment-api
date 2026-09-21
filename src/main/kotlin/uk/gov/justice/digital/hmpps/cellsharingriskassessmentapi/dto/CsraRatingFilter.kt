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
  STANDARD_LEGACY,
  HIGH,
  HIGH_GENERAL,
  HIGH_GENERAL_INTERIM,
  HIGH_GENERAL_PROVISIONAL,
  HIGH_SPECIFIC,
  HIGH_SPECIFIC_PROVISIONAL,
  MED,
  LOW,
  PEND,
  ;

  companion object {
    /**
     * The UI's preferred ordering for distinct rating values in history summaries and filter checklists.
     * This is intentionally explicit rather than relying on enum declaration order, because the legacy and
     * provisional variants do not sort naturally alongside the high/general/specific values.
     */
    private val uiOrder = listOf(
      HIGH,
      HIGH_GENERAL,
      HIGH_GENERAL_INTERIM,
      HIGH_GENERAL_PROVISIONAL,
      HIGH_SPECIFIC,
      HIGH_SPECIFIC_PROVISIONAL,
      STANDARD,
      STANDARD_LEGACY,
      LOW,
      MED,
      PEND,
    )

    fun ordered(values: Iterable<CsraRatingFilter>): List<CsraRatingFilter> =
      values.distinct().sortedWith(compareBy<CsraRatingFilter> { uiOrder.indexOf(it).takeIf { it >= 0 } ?: Int.MAX_VALUE })
  }

  /** The concrete [CsraResult] this filter matches, or null for [NO_RATING]. */
  fun toResult(): CsraResult? = when (this) {
    NO_RATING -> null
    STANDARD, STANDARD_LEGACY, MED, LOW -> CsraResult.STANDARD
    HIGH -> CsraResult.HIGH
    HIGH_GENERAL, HIGH_GENERAL_INTERIM, HIGH_GENERAL_PROVISIONAL -> CsraResult.HIGH_GENERAL
    HIGH_SPECIFIC, HIGH_SPECIFIC_PROVISIONAL -> CsraResult.HIGH_SPECIFIC
    PEND -> null
  }

  /** Whether this filter matches a prisoner's current rating and stage. */
  fun matches(rating: CsraResult?, stage: CsraRatingStage?): Boolean = when (this) {
    NO_RATING -> rating == null
    STANDARD -> rating == CsraResult.STANDARD && stage == CsraRatingStage.FINAL
    STANDARD_LEGACY -> rating == CsraResult.STANDARD
    HIGH -> rating == CsraResult.HIGH
    HIGH_GENERAL -> rating == CsraResult.HIGH_GENERAL && stage == CsraRatingStage.FINAL
    HIGH_GENERAL_INTERIM -> rating == CsraResult.HIGH_GENERAL && stage == CsraRatingStage.INTERIM
    HIGH_GENERAL_PROVISIONAL -> rating == CsraResult.HIGH_GENERAL && stage == CsraRatingStage.PROVISIONAL
    HIGH_SPECIFIC -> rating == CsraResult.HIGH_SPECIFIC && stage == CsraRatingStage.FINAL
    HIGH_SPECIFIC_PROVISIONAL -> rating == CsraResult.HIGH_SPECIFIC && stage == CsraRatingStage.PROVISIONAL
    MED, LOW -> rating == CsraResult.STANDARD
    PEND -> rating == null
  }
}
