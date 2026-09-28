package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import java.util.UUID

@Repository
interface CsraAssessmentStageRepository : JpaRepository<CsraAssessmentStageEntity, UUID> {
  fun findAllByCsraReviewId(csraReviewId: UUID): List<CsraAssessmentStageEntity>

  fun findAllByCsraReviewIdInAndStage(
    csraReviewIds: Collection<UUID>,
    stage: CsraAssessmentStage,
  ): List<CsraAssessmentStageEntity>

  /**
   * Used to build a page of history rows, so the risk selections are fetched alongside the stages rather
   * than lazily per row. DISTINCT prevents the two collection joins from returning duplicate stages.
   */
  @Query(
    """
    select distinct s
    from CsraAssessmentStageEntity s
    left join fetch s.riskTo
    left join fetch s.vulnerabilities
    where s.csraReview.id in :csraReviewIds
    """,
  )
  fun findAllByCsraReviewIdInWithRiskSelections(
    @Param("csraReviewIds") csraReviewIds: Collection<UUID>,
  ): List<CsraAssessmentStageEntity>

  fun findByCsraReviewIdAndStage(csraReviewId: UUID, stage: CsraAssessmentStage): CsraAssessmentStageEntity?

  fun existsByCsraReviewIdAndStage(csraReviewId: UUID, stage: CsraAssessmentStage): Boolean
}
