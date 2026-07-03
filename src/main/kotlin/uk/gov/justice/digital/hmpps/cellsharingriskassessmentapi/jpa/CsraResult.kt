package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The outcome of a CSRA review.
 *
 * - HIGH: legacy NOMIS "High" — cannot share a cell. NOMIS had no general/specific split, so migrated
 *   reviews use this value; it is kept distinct from the new-model [HIGH_GENERAL].
 * - HIGH_GENERAL: new (DPS) "High risk – general" — cannot share a cell with anyone.
 * - HIGH_SPECIFIC: "High risk – specific" — can share only with specific types of prisoner.
 * - STANDARD: can share.
 */
@Schema(description = "The outcome of a CSRA review")
enum class CsraResult {
  HIGH,
  HIGH_GENERAL,
  HIGH_SPECIFIC,
  STANDARD,
}
