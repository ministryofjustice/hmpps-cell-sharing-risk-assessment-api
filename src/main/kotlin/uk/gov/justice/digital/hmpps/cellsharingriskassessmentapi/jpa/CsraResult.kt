package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The outcome of a CSRA review.
 *
 * - HIGH: cannot share a cell.
 * - HIGH_SPECIFIC: can share only with specific types of prisoner.
 * - STANDARD: can share.
 */
@Schema(description = "The outcome of a CSRA review")
enum class CsraResult {
  HIGH,
  HIGH_SPECIFIC,
  STANDARD,
}
