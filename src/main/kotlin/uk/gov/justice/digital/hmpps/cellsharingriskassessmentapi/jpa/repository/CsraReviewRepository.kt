package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import java.util.UUID

@Repository
interface CsraReviewRepository :
  JpaRepository<CsraReviewEntity, UUID>,
  JpaSpecificationExecutor<CsraReviewEntity> {
  fun findAllByPrisonerNumberOrderByAssessmentDateDesc(prisonerNumber: String): List<CsraReviewEntity>

  /** The prisoner's most recent review (latest assessment date, then newest id), or null if none. */
  fun findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber: String): CsraReviewEntity?

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
}

/** Projection of [CsraReviewRepository.countCurrentRatingsByPrisonerNumberIn]: a current-rating value and its count. */
interface CurrentRatingCount {
  /** The stored [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult] name, or null for an in-progress (unrated) latest review. */
  val currentResult: String?
  val count: Long
}
