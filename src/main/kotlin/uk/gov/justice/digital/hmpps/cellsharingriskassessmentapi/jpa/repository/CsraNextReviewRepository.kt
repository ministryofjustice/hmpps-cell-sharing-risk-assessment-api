package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import java.util.UUID

@Repository
interface CsraNextReviewRepository : JpaRepository<CsraNextReviewEntity, UUID> {
  fun findByPrisonerNumber(prisonerNumber: String): CsraNextReviewEntity?

  fun findAllByPrisonerNumberIn(prisonerNumbers: Collection<String>): List<CsraNextReviewEntity>
}
