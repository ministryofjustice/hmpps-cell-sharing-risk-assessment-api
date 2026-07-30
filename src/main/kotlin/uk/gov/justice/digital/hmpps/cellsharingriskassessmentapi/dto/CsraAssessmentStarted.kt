package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * The result of starting a new initial CSRA assessment. The assessment id is the handle for the rest of the
 * journey; the current rating is reported separately because starting an assessment deliberately leaves the
 * prisoner's existing rating in place, so the two can refer to different reviews.
 */
@Schema(description = "The assessment that was started, alongside the prisoner's current rating")
data class CsraAssessmentStarted(
  @param:Schema(description = "The id of the assessment just started. Use this to submit the provisional and final stages.", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0", requiredMode = Schema.RequiredMode.REQUIRED)
  val assessmentId: UUID,

  @param:Schema(description = "The prisoner's current CSRA rating, which is unaffected by starting a new assessment and so may refer to an earlier review", requiredMode = Schema.RequiredMode.REQUIRED)
  val currentRating: CsraCurrentRating,
)
