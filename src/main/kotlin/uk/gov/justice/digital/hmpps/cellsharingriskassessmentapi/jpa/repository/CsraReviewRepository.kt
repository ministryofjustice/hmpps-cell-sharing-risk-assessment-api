package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.util.UUID

@Repository
interface CsraReviewRepository :
  JpaRepository<CsraReviewEntity, UUID>,
  JpaSpecificationExecutor<CsraReviewEntity> {
  fun findAllByPrisonerNumberOrderByAssessmentDateDesc(prisonerNumber: String): List<CsraReviewEntity>

  /** The prisoner's most recent review of any status (latest assessment date, then newest id), or null. */
  fun findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber: String): CsraReviewEntity?

  /** A prisoner's reviews in a given lifecycle state (e.g. the IN_PROGRESS one to close on admission). */
  fun findAllByPrisonerNumberAndStatus(prisonerNumber: String, status: CsraReviewStatus): List<CsraReviewEntity>

  /**
   * A prisoner's rated, non-[excludedStatus] reviews, most recent first — the first is the review that
   * sets the current rating (drives the csra_current_rating projection). Pass ARCHIVED for [excludedStatus].
   */
  @Query(
    """
    SELECT r FROM CsraReviewEntity r
    WHERE r.prisonerNumber = :prisonerNumber
      AND (r.finalResult IS NOT NULL OR r.interimResult IS NOT NULL)
      AND r.status <> :excludedStatus
    ORDER BY r.assessmentDate DESC, r.id DESC
    """,
  )
  fun findRatedReviews(prisonerNumber: String, excludedStatus: CsraReviewStatus): List<CsraReviewEntity>

  /**
   * Genuinely in-progress reviews of a given type at a prison — the "assessments/reviews in progress"
   * worklists. Callers pass a new-model type ([CsraType.CSRA_INITIAL_REVIEW] / [CsraType.CSRA_REVIEW]) and
   * status IN_PROGRESS, so completed rows (final result set), legacy rows (excluded by type) and closed/
   * archived rows (excluded by status) all drop out.
   */
  fun findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus(
    prisonId: String,
    type: CsraType,
    status: CsraReviewStatus,
  ): List<CsraReviewEntity>

  /** Every rated review for a prisoner, projected for computing whole-history summary counts. */
  @Query(
    """
    SELECT new uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraSummaryRow(
      r.finalResult, r.interimResult, r.assessmentDate, r.prisonId)
    FROM CsraReviewEntity r
    WHERE r.prisonerNumber = :prisonerNumber
      AND (r.finalResult IS NOT NULL OR r.interimResult IS NOT NULL)
    """,
  )
  fun findSummaryRows(prisonerNumber: String): List<CsraSummaryRow>
}
