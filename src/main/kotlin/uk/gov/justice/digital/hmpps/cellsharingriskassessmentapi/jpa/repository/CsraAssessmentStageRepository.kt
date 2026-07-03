package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import java.util.UUID

@Repository
interface CsraAssessmentStageRepository : JpaRepository<CsraAssessmentStageEntity, UUID> {
  fun findAllByCsraReviewId(csraReviewId: UUID): List<CsraAssessmentStageEntity>

  fun findAllByCsraReviewIdIn(csraReviewIds: Collection<UUID>): List<CsraAssessmentStageEntity>

  fun findByCsraReviewIdAndStage(csraReviewId: UUID, stage: CsraAssessmentStage): CsraAssessmentStageEntity?
}
