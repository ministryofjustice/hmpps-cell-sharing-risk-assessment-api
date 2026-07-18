package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType

/**
 * The coarse "Assessment type" column/filter on the prison prisoner list: every CSRA is either an
 * initial **Assessment** or a **Review**. The legacy NOMIS `REVIEW` and the new-model `CSRA_REVIEW`
 * map to REVIEW; everything else (initial/rating/reception/health/locate) maps to ASSESSMENT.
 */
@Schema(description = "The coarse CSRA assessment type: an initial assessment or a review")
enum class CsraAssessmentTypeBucket {
  ASSESSMENT,
  REVIEW,
}

/** Maps a [CsraType] to its coarse [CsraAssessmentTypeBucket]. */
fun CsraType.toAssessmentBucket(): CsraAssessmentTypeBucket = when (this) {
  CsraType.REVIEW, CsraType.CSRA_REVIEW -> CsraAssessmentTypeBucket.REVIEW
  else -> CsraAssessmentTypeBucket.ASSESSMENT
}
