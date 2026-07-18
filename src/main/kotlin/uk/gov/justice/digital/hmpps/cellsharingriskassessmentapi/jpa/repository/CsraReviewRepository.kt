package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.util.UUID

@Repository
interface CsraReviewRepository :
  JpaRepository<CsraReviewEntity, UUID>,
  JpaSpecificationExecutor<CsraReviewEntity> {
  fun findAllByPrisonerNumberOrderByAssessmentDateDesc(prisonerNumber: String): List<CsraReviewEntity>

  /** The prisoner's most recent review (latest assessment date, then newest id), or null if none. */
  fun findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber: String): CsraReviewEntity?

  /**
   * In-progress reviews (no final result yet) of a given type at a prison — the "assessments/reviews in
   * progress" worklists. Callers pass a new-model type ([CsraType.CSRA_INITIAL_REVIEW] / [CsraType.CSRA_REVIEW]);
   * legacy rows (which can also have a null result via NOMIS PEND) are excluded by the type filter.
   */
  fun findAllByPrisonIdAndTypeAndFinalResultIsNull(prisonId: String, type: CsraType): List<CsraReviewEntity>

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

  /**
   * For the given prisoners, counts how many have each current rating. A prisoner's current rating is
   * their latest review's `COALESCE(final_result, interim_result)` (consistent with
   * [findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc] / `getCurrentRating`). Prisoners whose
   * latest review has no saved rating appear under a `null` currentResult; prisoners with no review at
   * all are simply absent (they are "No rating", derived by the caller as roll − rated).
   */
  @Query(
    value = """
      SELECT current_result AS currentResult, COUNT(*) AS count
      FROM (
        SELECT DISTINCT ON (prisoner_number) COALESCE(final_result, interim_result) AS current_result
        FROM csra_review
        WHERE prisoner_number IN (:prisonerNumbers)
        ORDER BY prisoner_number, assessment_date DESC, id DESC
      ) latest
      GROUP BY current_result
    """,
    nativeQuery = true,
  )
  fun countCurrentRatingsByPrisonerNumberIn(prisonerNumbers: Collection<String>): List<CurrentRatingCount>

  /**
   * The latest review per prisoner for the given prisoners, projected for prison-scoped prisoner lists.
   * Same latest-review-per-prisoner semantics as [countCurrentRatingsByPrisonerNumberIn] /
   * [findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc]. Prisoners with no review are absent.
   */
  @Query(
    value = """
      SELECT DISTINCT ON (prisoner_number)
             prisoner_number AS prisonerNumber, final_result AS finalResult,
             interim_result AS interimResult, type AS type,
             assessment_date AS assessmentDate, final_result_date AS finalResultDate
      FROM csra_review
      WHERE prisoner_number IN (:prisonerNumbers)
      ORDER BY prisoner_number, assessment_date DESC, id DESC
    """,
    nativeQuery = true,
  )
  fun findCurrentReviewsByPrisonerNumberIn(prisonerNumbers: Collection<String>): List<CurrentReviewRow>
}

/** Projection of [CsraReviewRepository.countCurrentRatingsByPrisonerNumberIn]: a current-rating value and its count. */
interface CurrentRatingCount {
  /** The stored [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult] name, or null for an in-progress (unrated) latest review. */
  val currentResult: String?
  val count: Long
}

/** Projection of [CsraReviewRepository.findCurrentReviewsByPrisonerNumberIn]: a prisoner's latest review. */
interface CurrentReviewRow {
  val prisonerNumber: String

  /** Stored [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult] name, or null. */
  val finalResult: String?

  /** Stored [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult] name, or null. */
  val interimResult: String?

  /** Stored [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType] name. */
  val type: String
  val assessmentDate: LocalDate
  val finalResultDate: LocalDate?
}
