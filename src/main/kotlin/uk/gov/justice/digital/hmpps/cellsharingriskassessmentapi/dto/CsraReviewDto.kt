package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A new-model CSR review with everything saved on it so far, enough to resume it: Continue review, Back links,
 * Check answers and Change links all read this (MAPA-537). The review's counterpart of [CsraAssessmentDto],
 * which does not carry the review's own fields.
 */
@Schema(description = "A CSR review with the answers saved on each stage")
data class CsraReviewDto(
  @param:Schema(description = "The review id")
  val reviewId: UUID,

  @param:Schema(description = "The prisoner number")
  val prisonerNumber: String,

  @param:Schema(description = "The prison the review is currently associated with")
  val prisonId: String?,

  @param:Schema(description = "The review lifecycle status")
  val status: CsraReviewStatus,

  @param:Schema(description = "Username of the reviewer who started the review")
  val startedBy: String,

  @param:Schema(description = "When the review was started")
  val startedAt: LocalDateTime,

  @param:Schema(description = "Why the review is being held, from the final stage if one has been saved, otherwise the interim stage. Null until the first answers are saved.")
  val reviewReason: CsraReviewReason?,

  @param:Schema(description = "The interim rating, if one has been confirmed")
  val interimResult: CsraResult?,

  @param:Schema(description = "The final rating, if the review has been completed")
  val finalResult: CsraResult?,

  @param:Schema(description = "When this review says the prisoner's CSRA should next be reviewed, if it has set a date")
  val nextReviewDate: LocalDate?,

  @param:Schema(description = "One entry per stage that has been saved. Empty for a review started without answers.")
  val stages: List<CsraReviewStageDto>,
)

@Schema(description = "What has been saved on one stage of a CSR review")
data class CsraReviewStageDto(
  @param:Schema(description = "Which stage this is: INTERIM or FINAL")
  val stage: CsraAssessmentStage,

  @param:Schema(description = "The prison this stage was captured at")
  val prisonId: String?,

  @param:Schema(description = "The answers saved on this stage. They can be sent back unchanged in the next save, or spread into the confirm request.")
  val answers: CsraReviewAnswers,

  @param:Schema(description = "The review comment, once the stage has been confirmed")
  val reviewComment: String?,

  @param:Schema(description = "The name of who chaired the multidisciplinary meeting, once the stage has been confirmed")
  val mdtChairName: String?,

  @param:Schema(description = "Who the prisoner is a risk to, for a confirmed HIGH_SPECIFIC rating")
  val riskTo: List<CsraRiskToDetail>,

  @param:Schema(description = "The vulnerable or at-risk groups the prisoner belongs to, for a confirmed HIGH_SPECIFIC rating")
  val vulnerabilities: List<CsraVulnerabilityDetail>,

  @param:Schema(description = "Username of the reviewer who last saved this stage part way, if it has been")
  val lastSavedBy: String?,

  @param:Schema(description = "When this stage was last saved part way, if it has been")
  val lastSavedAt: LocalDateTime?,

  @param:Schema(description = "Username of the reviewer who confirmed this stage's rating, once confirmed")
  val completedBy: String?,

  @param:Schema(description = "When this stage's rating was confirmed, once confirmed")
  val completedAt: LocalDateTime?,

  @param:Schema(description = "The version of this stage's answers. Send it with the next save.", example = "1")
  val version: Int,
)

fun CsraReviewEntity.toReviewDto(stages: List<CsraAssessmentStageEntity>): CsraReviewDto {
  val ordered = stages.sortedBy { it.stage.ordinal }
  val latest = ordered.lastOrNull()
  return CsraReviewDto(
    reviewId = id!!,
    prisonerNumber = prisonerNumber,
    prisonId = prisonId,
    status = status,
    startedBy = createdBy,
    startedAt = createdAt,
    reviewReason = latest?.reviewReason,
    interimResult = interimResult,
    finalResult = finalResult,
    nextReviewDate = nextReviewDate,
    stages = ordered.map { it.toReviewStageDto() },
  )
}

fun CsraAssessmentStageEntity.toReviewStageDto() = CsraReviewStageDto(
  stage = stage,
  prisonId = prisonId,
  answers = toReviewAnswers(),
  reviewComment = assessmentComment,
  mdtChairName = mdtChairName,
  riskTo = riskTo.map { CsraRiskToDetail(category = it.category, details = it.details) },
  vulnerabilities = vulnerabilities.map { CsraVulnerabilityDetail(category = it.category, details = it.details) },
  lastSavedBy = lastSavedBy,
  lastSavedAt = lastSavedAt,
  completedBy = completedBy,
  completedAt = completedAt,
  version = version,
)
