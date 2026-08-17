package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEvidenceSourceEntity
import java.util.UUID

@Repository
interface CsraAssessmentStageEvidenceSourceRepository : JpaRepository<CsraAssessmentStageEvidenceSourceEntity, UUID> {
  /** A review stage's evidence sources, one per source selected. */
  fun findAllByStageId(stageId: UUID): List<CsraAssessmentStageEvidenceSourceEntity>
}
