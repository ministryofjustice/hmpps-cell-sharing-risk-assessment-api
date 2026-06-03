package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import java.util.UUID

@Repository
interface CsraReviewRepository : JpaRepository<CsraReviewEntity, UUID> {
  fun findAllByPrisonerNumberOrderByAssessmentDateDesc(prisonerNumber: String): List<CsraReviewEntity>
}
