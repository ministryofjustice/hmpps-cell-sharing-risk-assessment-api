package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

@Schema(description = "High risk prisoners in a prison who have a cell sharing risk review scheduled")
data class CsraHighRiskDueForReview(
  @param:Schema(description = "The prisoners due for review, filtered and sorted; not paginated")
  val content: List<CsraHighRiskReviewRow>,

  @param:Schema(description = "The number of prisoners in the (filtered) result", example = "11")
  val totalResults: Int,

  @param:Schema(
    description = "The distinct high-risk rating types present across the whole establishment's due list " +
      "(unaffected by filters), for the dynamic rating-type filter checkboxes",
  )
  val availableRatingTypes: List<CsraHighRiskType>,
)

@Schema(description = "A high risk prisoner due for a cell sharing risk review")
data class CsraHighRiskReviewRow(
  @param:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Callum")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Reid")
  val lastName: String?,

  @param:Schema(description = "The date the next cell sharing risk review is due", example = "2026-06-29")
  val reviewDueBy: LocalDate,

  @param:Schema(description = "The current high-risk rating type", example = "HIGH_GENERAL")
  val ratingType: CsraHighRiskType,

  @param:Schema(description = "The current rating value", example = "HIGH_GENERAL")
  val rating: CsraResult,

  @param:Schema(description = "Whether the current rating is provisional (an interim Day 1 rating)", example = "false")
  val provisional: Boolean,

  @param:Schema(description = "Whether the current rating was reached via an assessment or a review (drives 'Last assessed' vs 'Last reviewed')", example = "REVIEW")
  val lastRatingSource: CsraAssessmentTypeBucket,

  @param:Schema(description = "The date the current rating was recorded (the 'Last assessed'/'Last reviewed' date)", example = "2025-06-24")
  val lastRatingDate: LocalDate,
)
