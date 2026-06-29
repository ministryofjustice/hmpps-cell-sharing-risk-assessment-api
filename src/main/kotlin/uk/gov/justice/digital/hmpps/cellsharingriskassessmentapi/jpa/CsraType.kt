package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of CSRA assessment in the new (DPS) model. This is deliberately a clean enum, distinct
 * from the legacy NOMIS assessment type codes which are mapped onto it during migration/sync.
 */
@Schema(description = "The type of CSRA assessment")
enum class CsraType(
  legacy: Boolean = false,
) {
  FULL(true),
  HEALTH(true),
  LOCATE(true),
  RATING(true),
  RECEPTION(true),
  REVIEW(true),

  CSRA_INITIAL_REVIEW(false),
  CSRA_REVIEW(false),
}
