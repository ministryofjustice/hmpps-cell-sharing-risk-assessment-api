package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import java.util.UUID

@Repository
interface CsraCurrentRatingRepository : JpaRepository<CsraCurrentRatingEntity, UUID> {
  fun findByPrisonerNumber(prisonerNumber: String): CsraCurrentRatingEntity?

  fun findAllByPrisonerNumberIn(prisonerNumbers: Collection<String>): List<CsraCurrentRatingEntity>
}
