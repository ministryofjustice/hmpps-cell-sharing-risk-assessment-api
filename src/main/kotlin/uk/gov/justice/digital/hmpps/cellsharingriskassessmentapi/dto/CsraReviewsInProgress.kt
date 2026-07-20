package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A prison's in-progress CSRA reviews")
data class CsraReviewsInProgress(
  @param:Schema(description = "The reviews currently in progress")
  val content: List<CsraReviewInProgressRow>,

  @param:Schema(description = "The number of reviews in progress", example = "3")
  val totalResults: Int,
)

@Schema(description = "A cell sharing risk review in progress")
data class CsraReviewInProgressRow(
  @param:Schema(description = "The CSRA review id (for continuing or cancelling the review)", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val reviewId: UUID,

  @param:Schema(description = "The prisoner number", example = "A9354JF")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Simon")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Kettleby")
  val lastName: String?,

  @param:Schema(description = "The date the review was started", example = "2026-07-03")
  val startedOn: LocalDate,

  @param:Schema(description = "The username of who started the review", example = "SCARTER")
  val startedBy: String,
)
