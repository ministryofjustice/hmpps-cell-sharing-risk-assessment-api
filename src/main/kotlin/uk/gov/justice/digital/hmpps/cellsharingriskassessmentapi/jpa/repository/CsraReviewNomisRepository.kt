package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import java.util.UUID

@Repository
interface CsraReviewNomisRepository : JpaRepository<CsraReviewNomisEntity, UUID> {
  fun findByCsraReviewId(csraReviewId: UUID): CsraReviewNomisEntity?

  fun findAllByCsraReviewIdIn(csraReviewIds: Collection<UUID>): List<CsraReviewNomisEntity>

  /**
   * Prisoners whose current rating or next review date is set by a NOMIS review that was rejected at approval.
   * Rated before MAPA-526 excluded rejected reviews from the rating; used once to put those right.
   */
  @Query(
    nativeQuery = true,
    value = """
    SELECT c.prisoner_number
    FROM csra_current_rating c
    JOIN csra_review_nomis n ON n.csra_review_id = c.set_by_review_id
    WHERE n.evaluation_result_code = 'REJ'
    UNION
    SELECT x.prisoner_number
    FROM csra_next_review x
    JOIN csra_review_nomis n ON n.csra_review_id = x.set_by_review_id
    WHERE n.evaluation_result_code = 'REJ'
    """,
  )
  fun findPrisonerNumbersRatedByRejectedReview(): List<String>
}
