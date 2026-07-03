package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import java.util.UUID

@Repository
interface CsraReviewNomisRepository : JpaRepository<CsraReviewNomisEntity, UUID> {
  fun findByCsraReviewId(csraReviewId: UUID): CsraReviewNomisEntity?

  fun findAllByCsraReviewIdIn(csraReviewIds: Collection<UUID>): List<CsraReviewNomisEntity>
}
