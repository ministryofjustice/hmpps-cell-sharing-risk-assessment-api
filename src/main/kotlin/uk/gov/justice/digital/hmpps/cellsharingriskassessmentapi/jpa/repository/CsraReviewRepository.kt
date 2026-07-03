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
      r.finalResult, r.interimResult, r.assessmentDate)
    FROM CsraReviewEntity r
    WHERE r.prisonerNumber = :prisonerNumber
      AND (r.finalResult IS NOT NULL OR r.interimResult IS NOT NULL)
    """,
  )
  fun findSummaryRows(prisonerNumber: String): List<CsraSummaryRow>
}
