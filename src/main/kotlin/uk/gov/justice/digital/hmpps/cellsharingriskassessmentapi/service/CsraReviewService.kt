package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewHistory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewHistorySummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewSummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.isHigh
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toResults
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewSpecifications
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CsraReviewService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
) {
  fun getCsraReviewById(id: UUID): CsraReview? = csraReviewRepository.findByIdOrNull(id)?.toDto()

  fun getCsraHistory(
    prisonerNumber: String,
    page: Int,
    size: Int,
    fromDate: LocalDate?,
    toDate: LocalDate?,
    establishments: List<String>?,
    ratings: List<CsraRatingBucket>?,
  ): CsraReviewHistory {
    val results = ratings?.flatMap { it.toResults() }?.distinct()?.takeIf { it.isNotEmpty() }
    val spec = CsraReviewSpecifications.history(prisonerNumber, fromDate, toDate, establishments, results)
    val pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("assessmentDate"), Sort.Order.desc("id")))
    val reviews = csraReviewRepository.findAll(spec, pageable)

    val reviewIds = reviews.content.mapNotNull { it.id }
    val commentByReviewId = resolveComments(reviewIds)
    val content = reviews.content.map { it.toSummary(commentByReviewId[it.id]) }

    return CsraReviewHistory(
      summary = buildSummary(prisonerNumber),
      content = content,
      page = reviews.number,
      size = reviews.size,
      totalElements = reviews.totalElements,
      totalPages = reviews.totalPages,
    )
  }

  private fun buildSummary(prisonerNumber: String): CsraReviewHistorySummary {
    val rows = csraReviewRepository.findSummaryRows(prisonerNumber)
    val highDates = rows.filter { it.result.isHigh() }.map { it.assessmentDate }
    val dates = rows.map { it.assessmentDate }
    return CsraReviewHistorySummary(
      totalCsras = rows.size,
      highCount = highDates.size,
      standardCount = rows.size - highDates.size,
      firstAssessmentDate = dates.minOrNull(),
      lastAssessmentDate = dates.maxOrNull(),
      lastHighDate = highDates.maxOrNull(),
    )
  }

  /**
   * Resolves the display comment for each review. The comment lives in a different place per row type:
   * new-model reviews carry it on the FINAL (or, failing that, PROVISIONAL) assessment stage; migrated
   * legacy reviews carry it on the adjacent NOMIS record. Loaded in batch to avoid N+1 queries.
   */
  private fun resolveComments(reviewIds: List<UUID>): Map<UUID, String?> {
    if (reviewIds.isEmpty()) return emptyMap()
    val stagesByReviewId = csraAssessmentStageRepository.findAllByCsraReviewIdIn(reviewIds)
      .groupBy { it.csraReview.id }
    val nomisByReviewId = csraReviewNomisRepository.findAllByCsraReviewIdIn(reviewIds)
      .associateBy { it.csraReview.id }
    return reviewIds.associateWith { id ->
      stageComment(stagesByReviewId[id]) ?: nomisComment(nomisByReviewId[id])
    }
  }

  private fun stageComment(stages: List<CsraAssessmentStageEntity>?): String? {
    if (stages.isNullOrEmpty()) return null
    return stages.firstOrNull { it.stage == CsraAssessmentStage.FINAL }?.assessmentComment
      ?: stages.firstOrNull { it.stage == CsraAssessmentStage.PROVISIONAL }?.assessmentComment
  }

  private fun nomisComment(nomis: CsraReviewNomisEntity?): String? = nomis?.reviewComment ?: nomis?.comment

  private fun CsraReviewEntity.toSummary(reviewComment: String?) = CsraReviewSummary(
    id = id!!,
    type = type,
    rating = finalResult ?: interimResult!!,
    reviewComment = reviewComment,
    prisonId = prisonId,
    recordedDate = finalResultDate ?: assessmentDate,
  )
}
