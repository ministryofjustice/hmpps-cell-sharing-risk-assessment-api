package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of CSRA assessment in the new (DPS) model. This is deliberately a clean enum, distinct
 * from the legacy NOMIS assessment type codes which are mapped onto it during migration/sync.
 *
 * Two values were renamed in V20 (MAPA-367), because the old names misled: `CSRA_INITIAL_ASSESSMENT`
 * was `CSRA_INITIAL_REVIEW` — it is an assessment, and was routinely read as a review — and
 * `NOMIS_REVIEW` was `REVIEW`, which collided with the new-model `CSRA_REVIEW`.
 *
 * The old names survive in two places and cannot be tidied away. Migrations before V20 match on them
 * and are correct as a record of what ran. **Audit payloads written before V20 also contain them** —
 * the [CsraReview][uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview] DTO is
 * serialised as the audit `details` — so anything querying the audit store by type needs both names
 * indefinitely.
 *
 * Prefer [CsraAssessmentTypeBucket] — exposed as `assessmentType` alongside every `type` on the API —
 * when all you need is "assessment or review".
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
  NOMIS_REVIEW(true),

  CSRA_INITIAL_ASSESSMENT(false),
  CSRA_REVIEW(false),
}
