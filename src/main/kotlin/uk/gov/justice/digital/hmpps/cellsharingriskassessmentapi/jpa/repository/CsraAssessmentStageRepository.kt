package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import java.util.UUID

@Repository
interface CsraAssessmentStageRepository : JpaRepository<CsraAssessmentStageEntity, UUID> {
  fun findAllByCsraReviewId(csraReviewId: UUID): List<CsraAssessmentStageEntity>

  /**
   * Used to build a page of history rows, so the risk selections are fetched alongside the stages rather
   * than lazily per row. Both are sets, so joining them in one query is safe.
   */
  @EntityGraph(attributePaths = ["riskTo", "vulnerabilities"])
  fun findAllByCsraReviewIdIn(csraReviewIds: Collection<UUID>): List<CsraAssessmentStageEntity>

  fun findByCsraReviewIdAndStage(csraReviewId: UUID, stage: CsraAssessmentStage): CsraAssessmentStageEntity?

  fun existsByCsraReviewIdAndStage(csraReviewId: UUID, stage: CsraAssessmentStage): Boolean
}
