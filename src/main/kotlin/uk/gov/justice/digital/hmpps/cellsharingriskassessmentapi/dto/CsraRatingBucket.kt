package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

/**
 * A coarse rating bucket used for filtering and summarising CSRA history. The UI groups all "high"
 * variants (legacy and new-model general/specific) under a single "High" heading, and everything else
 * under "Standard".
 */
@Schema(description = "A coarse CSRA rating bucket for filtering and summary counts")
enum class CsraRatingBucket {
  HIGH,
  STANDARD,
}

/** The [CsraResult] values that count as "high risk". */
val HIGH_RESULTS: Set<CsraResult> = setOf(CsraResult.HIGH, CsraResult.HIGH_GENERAL, CsraResult.HIGH_SPECIFIC)

/** Expands a filter bucket to the concrete [CsraResult] values it covers. */
fun CsraRatingBucket.toResults(): List<CsraResult> = when (this) {
  CsraRatingBucket.HIGH -> HIGH_RESULTS.toList()
  CsraRatingBucket.STANDARD -> listOf(CsraResult.STANDARD)
}

/** Whether this result belongs to the "high risk" bucket. */
fun CsraResult.isHigh(): Boolean = this in HIGH_RESULTS
