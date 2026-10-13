package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "What is needed to cancel an unrated initial CSRA assessment")
data class CsraAssessmentCancelRequest(
  @param:Schema(description = "The prison requesting cancellation, used for the rollout check", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,
)
