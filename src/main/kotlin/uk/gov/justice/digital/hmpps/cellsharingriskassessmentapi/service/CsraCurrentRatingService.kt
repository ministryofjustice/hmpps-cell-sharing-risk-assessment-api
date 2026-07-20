package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toAssessmentBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * Maintains the per-prisoner [CsraCurrentRatingEntity] projection — the single source of truth for a
 * prisoner's current CSRA rating (R-06/R-07). It is updated only when a rating is saved (assessment/review
 * journey, or NOMIS migration/sync) or when a readmission after release resets it to "No rating" (R-01);
 * merely starting a new assessment leaves it unchanged, so the prior rating persists.
 */
@Service
@Transactional
class CsraCurrentRatingService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val clock: Clock,
) {
  /**
   * Recomputes a prisoner's current rating from their latest rated, non-archived review (or clears it to
   * "No rating" if none). Call after a rating is saved, migrated or synchronised.
   */
  fun refreshFromReviews(prisonerNumber: String, updatedBy: String? = null) {
    val latestRated = csraReviewRepository.findRatedReviews(prisonerNumber, CsraReviewStatus.ARCHIVED).firstOrNull()
    if (latestRated == null) {
      upsert(prisonerNumber, updatedBy, CsraRatingSetReason.RATING_SAVED) {
        rating = null
        provisional = false
        assessmentType = null
        ratingDate = null
        setByReviewId = null
      }
      return
    }
    upsert(prisonerNumber, updatedBy, CsraRatingSetReason.RATING_SAVED) { applyFrom(latestRated) }
  }

  /** Resets a prisoner's current rating to "No rating" (R-01 readmission after release). */
  fun resetToNoRating(prisonerNumber: String, updatedBy: String?) {
    upsert(prisonerNumber, updatedBy, CsraRatingSetReason.NO_RATING_ON_READMISSION) {
      rating = null
      provisional = false
      assessmentType = null
      ratingDate = null
      setByReviewId = null
    }
  }

  private fun CsraCurrentRatingEntity.applyFrom(review: CsraReviewEntity) {
    rating = review.finalResult ?: review.interimResult
    provisional = review.finalResult == null && review.interimResult != null
    assessmentType = review.type.toAssessmentBucket()
    ratingDate = review.finalResultDate ?: review.assessmentDate
    setByReviewId = review.id
  }

  private fun upsert(
    prisonerNumber: String,
    updatedBy: String?,
    reason: CsraRatingSetReason,
    apply: CsraCurrentRatingEntity.() -> Unit,
  ) {
    val entity = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)
      ?: CsraCurrentRatingEntity(prisonerNumber = prisonerNumber, setReason = reason, setAt = LocalDateTime.now(clock))
    entity.apply(apply)
    entity.setReason = reason
    entity.setAt = LocalDateTime.now(clock)
    entity.setBy = updatedBy
    csraCurrentRatingRepository.save(entity)
  }
}
