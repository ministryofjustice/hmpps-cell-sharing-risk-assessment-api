package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "What is needed to cancel a CSR review that has no rating yet")
data class CsraReviewCancelRequest(
  @param:Schema(description = "The prison requesting cancellation, used for the rollout check", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,
)
