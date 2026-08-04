package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageOffenceEvidenceEntity
import java.util.UUID

@Repository
interface CsraAssessmentStageOffenceEvidenceRepository : JpaRepository<CsraAssessmentStageOffenceEvidenceEntity, UUID> {
  /** A stage's evidence records, one per offence answered Yes. */
  fun findAllByStageId(stageId: UUID): List<CsraAssessmentStageOffenceEvidenceEntity>
}
