package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** The state of a CSRA rating. */
@Schema(description = "The state of a CSRA rating")
enum class CsraRatingStatus {
  @Schema(description = "There is no rating and no assessment/review in progress")
  NO_RATING,

  @Schema(description = "An assessment/review has been started but no rating has been given yet")
  IN_PROGRESS,

  @Schema(description = "A provisional/interim rating has been given but no final rating has been confirmed")
  PROVISIONAL,

  @Schema(description = "The assessment/review has a final rating")
  COMPLETE,
}

@Schema(description = "A CSRA rating and its supporting detail, returned for the prisoner's current rating or a selected assessment/review")
data class CsraDetailedRating(
  @param:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The state of the rating")
  val status: CsraRatingStatus,

  @param:Schema(description = "The rating (final if complete, otherwise the provisional/interim rating). Absent when there is no rating yet.", example = "STANDARD")
  val rating: CsraResult?,

  @param:Schema(description = "The reason the current rating was set, if any", example = "RATING_SAVED")
  val reason: CsraRatingSetReason? = null,

  @param:Schema(description = "Whether the rating is provisional (a final rating has not yet been confirmed)", example = "false")
  val provisional: Boolean,

  @param:Schema(description = "Whether the provisional or interim rating was inherited because the prisoner transferred before it was completed. Use ratingStage to decide which copy to show.", example = "false")
  val inheritedAfterTransfer: Boolean,

  @param:Schema(description = "Which stage the rating came from. Distinguishes an assessment's provisional rating from a review's interim one, which the provisional flag cannot. Null when there is no rating.", example = "FINAL")
  val ratingStage: CsraRatingStage?,

  @param:Schema(description = "The id of the assessment/review the rating belongs to, if any", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val reviewId: UUID?,

  @param:Schema(description = "The prison the assessment/review took place at", example = "LEI")
  val prisonId: String?,

  @param:Schema(description = "The name of the prison the assessment/review took place at, falling back to the id if it cannot be resolved", example = "Leeds (HMP)")
  val prisonName: String?,

  @param:Schema(description = "The final assessment comment (or, for a migrated legacy review, its review comment)", example = "PNC checked. No issues found.")
  val assessmentComment: String?,

  @param:Schema(description = "The provisional (Day 1) assessment comment, when the assessment was captured over two stages", example = "PNC not checked on day 1. No evidence of increased risk.")
  val provisionalAssessmentComment: String?,

  @param:Schema(description = "For a high-risk-specific rating, who the prisoner is a risk to")
  val riskTo: List<CsraRiskToDetail>,

  @param:Schema(description = "For a high-risk-specific rating, the groups the prisoner is vulnerable due to")
  val vulnerabilities: List<CsraVulnerabilityDetail>,

  @param:Schema(description = "The date the provisional (Day 1) rating was given, if any", example = "2026-06-30")
  val provisionalDate: LocalDate?,

  @param:Schema(description = "The date the final rating was given, if any", example = "2026-07-01")
  val finalDate: LocalDate?,

  @param:Schema(description = "The prisoner's next review date for current-rating, or the selected record's next review date for detailed-rating, if any", example = "2027-05-06")
  val nextReviewDate: LocalDate?,

  @param:Schema(description = "The username that started the assessment/review", example = "BPONDS")
  val startedBy: String?,

  @param:Schema(description = "When the assessment/review was started", example = "2026-06-26T11:20:00")
  val startedAt: LocalDateTime?,

  @param:Schema(description = "The exact kind of record the rating came from, legacy NOMIS types included. Absent when there is no rating. Note CSRA_INITIAL_ASSESSMENT is an assessment — use `assessmentType` unless you need the precise type.", example = "CSRA_INITIAL_ASSESSMENT")
  val type: CsraType? = null,

  @param:Schema(description = "Whether the rating came from an assessment or a review. Absent when there is no rating.", example = "ASSESSMENT")
  val assessmentType: CsraAssessmentTypeBucket? = null,

  @param:Schema(description = "For current-rating, the prisoner's assessment or review currently in progress. For detailed-rating, the selected record if it is in progress. Absent otherwise.")
  val inProgress: CsraInProgressReview? = null,

  @param:Schema(description = "The username that completed the provisional or interim rating, if recorded", example = "BPONDS")
  val provisionalCompletedBy: String? = null,

  @param:Schema(description = "The username that completed the final rating, if recorded", example = "NQP56Y")
  val finalCompletedBy: String? = null,

  @param:Schema(description = "The time the provisional or interim rating was completed, if recorded", example = "10:30:00")
  val provisionalTime: LocalTime? = null,

  @param:Schema(description = "The time the final rating was completed, if recorded", example = "11:20:00")
  val finalTime: LocalTime? = null,
)

/**
 * An assessment or review that is started but not yet complete, reported alongside — not instead of — the
 * prisoner's current rating.
 *
 * The two can be the same record or different ones, and a consumer tells them apart by comparing this
 * [reviewId] with [CsraDetailedRating.reviewId]:
 *
 * - **Equal** — the in-progress record is the one showing the current rating, i.e. it has saved an
 *   interim/provisional rating that now stands. The screen reads "an interim rating has been entered,
 *   complete the review to confirm a final rating".
 * - **Different** — the rating comes from an earlier completed record and this one is still unrated. The
 *   screen shows the old rating with a separate "review in progress" block.
 *
 * An in-progress record either carries no rating at all or its rating *is* the current rating, so no rating
 * is repeated here.
 */
@Schema(description = "An assessment or review in progress, reported alongside the current rating")
data class CsraInProgressReview(
  @param:Schema(description = "The id of the in-progress assessment or review. Point 'Continue' and 'Cancel' at this.", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0", requiredMode = Schema.RequiredMode.REQUIRED)
  val reviewId: UUID,

  @param:Schema(description = "The exact kind of record in progress. Always a new-model type: a migrated NOMIS review is never in progress, so the legacy values cannot appear here.", example = "CSRA_INITIAL_ASSESSMENT", requiredMode = Schema.RequiredMode.REQUIRED)
  val type: CsraType,

  @param:Schema(description = "Whether this is an assessment or a review — the card is captioned accordingly", example = "ASSESSMENT", requiredMode = Schema.RequiredMode.REQUIRED)
  val assessmentType: CsraAssessmentTypeBucket,

  @param:Schema(description = "The username that started it", example = "BPONDS")
  val startedBy: String?,

  @param:Schema(description = "When it was started", example = "2026-06-26T11:20:00")
  val startedAt: LocalDateTime?,

  @param:Schema(description = "The prison it was started at", example = "LEI")
  val prisonId: String?,

  @param:Schema(description = "The name of the prison it was started at, falling back to the id if it cannot be resolved", example = "Brixton (HMP)")
  val prisonName: String?,
)

@Schema(description = "A group the prisoner is a risk to, with optional free-text detail")
data class CsraRiskToDetail(
  @param:Schema(description = "The risk-to category", example = "DIFFERENT_ETHNICITY")
  val category: CsraRiskToCategory,

  @param:Schema(description = "Optional free text describing the risk", example = "Racist towards other ethnicities.")
  val details: String?,
)

@Schema(description = "A group the prisoner is vulnerable due to, with optional free-text detail")
data class CsraVulnerabilityDetail(
  @param:Schema(description = "The vulnerability category", example = "NEURODIVERSITY")
  val category: CsraVulnerabilityCategory,

  @param:Schema(description = "Optional free text describing the vulnerability", example = "Autistic.")
  val details: String?,
)
