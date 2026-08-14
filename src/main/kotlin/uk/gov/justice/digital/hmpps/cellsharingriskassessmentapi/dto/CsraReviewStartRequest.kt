package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * What is needed to start a new-model CSR review. As with an initial assessment, the prisoner comes from
 * the path and the reviewer from the authenticated user, so the prison is the only thing the caller has to
 * supply — and it is required, because a draft with no prison appears on no prison's worklist.
 */
@Schema(description = "What is needed to start a CSR review")
data class CsraReviewStartRequest(
  @param:Schema(description = "The prison the review is being started at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,
)

/**
 * The review that was started, alongside the prisoner's current rating — which starting a review leaves
 * untouched, so it still describes the earlier assessment or review the reviewer is revisiting.
 */
@Schema(description = "The review that was started, alongside the prisoner's current rating")
data class CsraReviewStarted(
  @param:Schema(description = "The id of the review just started. Use this to submit the interim and final stages.", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0", requiredMode = Schema.RequiredMode.REQUIRED)
  val reviewId: UUID,

  @param:Schema(description = "The prisoner's current CSRA rating, which is unaffected by starting a review and so describes the rating being reviewed", requiredMode = Schema.RequiredMode.REQUIRED)
  val currentRating: CsraCurrentRating,
)
