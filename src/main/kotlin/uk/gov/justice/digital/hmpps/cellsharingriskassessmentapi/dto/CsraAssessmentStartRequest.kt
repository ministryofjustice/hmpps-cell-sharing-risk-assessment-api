package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

/**
 * What is needed to start a new-model initial CSRA assessment. The prisoner comes from the path and the
 * officer from the authenticated user, so the prison is the only thing the caller has to supply.
 *
 * It is required because a draft with no prison appears on no prison's worklist, which is precisely the
 * screen an in-progress assessment exists to show up on.
 */
@Schema(description = "What is needed to start an initial CSRA assessment")
data class CsraAssessmentStartRequest(
  @param:Schema(description = "The prison the assessment is being started at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,
)
