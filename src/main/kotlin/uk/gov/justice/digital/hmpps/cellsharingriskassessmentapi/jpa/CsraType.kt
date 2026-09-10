package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of CSRA assessment in the new (DPS) model. This is deliberately a clean enum, distinct
 * from the legacy NOMIS assessment type codes which are mapped onto it during migration/sync.
 *
 * Two of these names mislead, and will until MAPA-367 renames them: **`CSRA_INITIAL_REVIEW` is an
 * assessment**, not a review, and the legacy `REVIEW` is a NOMIS review rather than the new-model
 * `CSRA_REVIEW`. Prefer [CsraAssessmentTypeBucket] — exposed as `assessmentType` alongside every
 * `type` on the API — when all you need is "assessment or review".
 */
@Schema(description = "The type of CSRA assessment")
enum class CsraType(
  /** True for the legacy NOMIS type codes mapped onto this enum during migration/sync. */
  val legacy: Boolean = false,
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
