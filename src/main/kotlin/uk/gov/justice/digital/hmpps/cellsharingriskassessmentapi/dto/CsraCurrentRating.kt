package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** The state of a prisoner's current CSRA rating. */
@Schema(description = "The state of a prisoner's current CSRA rating")
enum class CsraRatingStatus {
  @Schema(description = "The prisoner has no CSRA and requires an assessment")
  NO_RATING,

  @Schema(description = "An assessment has been started but no rating (not even a provisional one) has been given yet")
  IN_PROGRESS,

  @Schema(description = "A provisional (Day 1) rating has been given; the assessment is not yet complete")
  PROVISIONAL,

  @Schema(description = "The assessment is complete with a final rating")
  COMPLETE,
}

@Schema(description = "A prisoner's current CSRA rating and its supporting detail")
data class CsraCurrentRating(
  @param:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The state of the current rating")
  val status: CsraRatingStatus,

  @param:Schema(description = "The current rating (final if complete, otherwise the provisional/interim rating). Absent when there is no rating yet.", example = "STANDARD")
  val rating: CsraResult?,

  @param:Schema(description = "Whether the current rating is provisional (a final rating has not yet been confirmed)", example = "false")
  val provisional: Boolean,

  @param:Schema(description = "The id of the review that produced the current rating, if any", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val reviewId: UUID?,

  @param:Schema(description = "The prison the current assessment/review took place at", example = "LEI")
  val prisonId: String?,

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

  @param:Schema(description = "The date the prisoner's next review is due, if any", example = "2027-05-06")
  val nextReviewDate: LocalDate?,

  @param:Schema(description = "The username that started the current assessment", example = "BPONDS")
  val startedBy: String?,

  @param:Schema(description = "When the current assessment was started", example = "2026-06-26T11:20:00")
  val startedAt: LocalDateTime?,

  @param:Schema(description = "Whether the rating came from an assessment or a review. Absent when there is no rating.", example = "CSRA_INITIAL_REVIEW")
  val type: CsraType? = null,

  @param:Schema(description = "The assessment or review currently in progress, if any. Absent when nothing is in progress.")
  val inProgress: CsraInProgressReview? = null,
)

/**
 * An assessment or review that is started but not yet complete, reported alongside — not instead of — the
 * prisoner's current rating.
 *
 * The two can be the same record or different ones, and a consumer tells them apart by comparing this
 * [reviewId] with [CsraCurrentRating.reviewId]:
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

  @param:Schema(description = "Whether this is an assessment or a review — the card is captioned accordingly", example = "CSRA_INITIAL_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
  val type: CsraType,

  @param:Schema(description = "The username that started it", example = "BPONDS")
  val startedBy: String?,

  @param:Schema(description = "When it was started", example = "2026-06-26T11:20:00")
  val startedAt: LocalDateTime?,

  @param:Schema(description = "The prison it was started at", example = "LEI")
  val prisonId: String?,
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
