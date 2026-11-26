package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * What is needed to start a new-model CSR review. As with an initial assessment, the prisoner comes from
 * the path and the reviewer from the authenticated user, so the prison is the only thing the caller has to
 * supply — and it is required, because a draft with no prison appears on no prison's worklist.
 *
 * The designs first save a review when its evidence sources page is saved, so that page starts the review
 * and saves its answers in one call (MAPA-537). Doing it in two would leave an empty review in progress if
 * the second call failed, which the designs say must not happen. The one journey that starts a review with
 * no answers is the interim route, whose next call is the interim confirm.
 */
@Schema(description = "What is needed to start a CSR review")
data class CsraReviewStartRequest(
  @param:Schema(description = "The prison the review is being started at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @param:Schema(description = "The first answers to save, on the review's final stage. Omit to start a review with nothing saved, as the interim rating route does.")
  @field:Valid
  val answers: CsraReviewAnswers? = null,
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
  val currentRating: CsraDetailedRating,
)
