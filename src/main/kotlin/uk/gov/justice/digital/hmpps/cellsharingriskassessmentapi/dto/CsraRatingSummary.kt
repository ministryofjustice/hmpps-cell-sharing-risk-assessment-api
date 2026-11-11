package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate
import java.util.UUID

/**
 * A prisoner's current CSRA rating in brief, for other services that only need the rating and the words to
 * show for it (MAPA-516), such as Prisoner Search and cell move. Read from the current-rating projection, so
 * it is cheap enough to ask for many prisoners at once. The full picture is [CsraDetailedRating].
 */
@Schema(description = "A prisoner's current CSRA rating in brief: the rating and the text to show for it")
data class CsraRatingSummary(
  @param:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The state of the rating. IN_PROGRESS only when there is no rating and an assessment or review has been started.")
  val status: CsraRatingStatus,

  @param:Schema(description = "The rating (final if complete, otherwise provisional or interim). Absent when there is no rating.", example = "HIGH_GENERAL")
  val rating: CsraResult?,

  @param:Schema(description = "Which stage the rating came from. Absent when there is no rating.", example = "FINAL")
  val ratingStage: CsraRatingStage?,

  @param:Schema(description = "The date the rating was given. Absent when there is no rating.", example = "2026-11-03")
  val ratingDate: LocalDate?,

  @param:Schema(description = "The assessment or review that gave the rating. Absent when there is no rating.")
  val reviewId: UUID?,

  @param:Schema(description = "The rating in full, to show as given. \"No rating\" when there is no rating.", example = "High risk – general (interim)")
  val ratingDescription: String,

  @param:Schema(description = "The rating in short, to show as given, for example in the prisoner profile header. Never includes the stage. \"No rating\" when there is no rating.", example = "High – general")
  val ratingShortDescription: String,

  @param:Schema(description = "The review due date to show beside the rating, or absent where none should be shown (Standard, No rating and provisional ratings).", example = "2027-05-06")
  val reviewDueDate: LocalDate?,
)
