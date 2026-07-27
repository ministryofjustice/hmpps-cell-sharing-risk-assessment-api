package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.ActiveAgencyEntity
import java.util.UUID

@Repository
interface ActiveAgencyRepository : JpaRepository<ActiveAgencyEntity, UUID> {
  fun findByAgencyId(agencyId: String): ActiveAgencyEntity?

  fun findAllByActiveTrue(): List<ActiveAgencyEntity>
}
