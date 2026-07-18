package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

/**
 * The high-risk rating types shown on the "high risk prisoners due for review" screen — the current
 * rating value combined with whether it is provisional (an interim Day 1 rating). Only high-risk
 * ratings map to a value; a provisional High – general is an interim rating.
 */
@Schema(description = "A high-risk CSRA rating type for the due-for-review worklist and its filter")
enum class CsraHighRiskType {
  HIGH,
  HIGH_GENERAL,
  HIGH_GENERAL_INTERIM,
  HIGH_SPECIFIC,
  ;

  companion object {
    /**
     * The high-risk rating type for a current rating, or null if the rating is not high-risk. A
     * provisional High – general is the interim variant; per the CSRA journey provisional high is
     * always general (a provisional high-specific maps to [HIGH_SPECIFIC]).
     */
    fun from(rating: CsraResult, provisional: Boolean): CsraHighRiskType? = when (rating) {
      CsraResult.HIGH -> HIGH
      CsraResult.HIGH_GENERAL -> if (provisional) HIGH_GENERAL_INTERIM else HIGH_GENERAL
      CsraResult.HIGH_SPECIFIC -> HIGH_SPECIFIC
      CsraResult.STANDARD -> null
    }
  }
}
