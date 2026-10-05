package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.ratingStageFor
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toAssessmentBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.nextReviewDateOrNomis
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * Maintains the two per-prisoner projections derived from a prisoner's reviews.
 *
 * [CsraCurrentRatingEntity] is the single source of truth for a prisoner's current CSRA rating (R-06/R-07).
 * It is updated only when a rating is saved (assessment/review journey, or NOMIS migration/sync), after a
 * merge or booking move, or when a readmission after release resets it to "No rating" (R-01); merely
 * starting a new assessment leaves it unchanged, so the prior rating persists.
 *
 * [CsraNextReviewEntity] is the prisoner's current next review date, re-derived alongside the rating from the
 * date each review set (SDIT-4297). No other path writes it, so anything that changes which reviews a
 * prisoner holds — a merge, a booking move — gets the right date by calling [refreshFromReviews].
 */
@Service
@Transactional
class CsraCurrentRatingService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val clock: Clock,
) {
  /**
   * Recomputes a prisoner's current rating from their latest rated, non-archived review (or clears it to
   * "No rating" if none), and their next review date from their latest *final*-rated one. Call after a
   * rating is saved, migrated or synchronised, or after reviews move between prisoner numbers.
   */
  fun refreshFromReviews(prisonerNumber: String, updatedBy: String? = null) {
    val ratedReviews = csraReviewRepository.findRatedReviews(prisonerNumber, CsraReviewStatus.ARCHIVED)
    refreshNextReview(prisonerNumber, ratedReviews.firstOrNull { it.finalResult != null }, updatedBy)

    val latestRated = ratedReviews.firstOrNull()
    if (latestRated == null) {
      upsert(prisonerNumber, updatedBy, CsraRatingSetReason.RATING_SAVED) {
        rating = null
        provisional = false
        ratingStage = null
        assessmentType = null
        ratingDate = null
        setByReviewId = null
      }
      return
    }
    upsert(prisonerNumber, updatedBy, CsraRatingSetReason.RATING_SAVED) { applyFrom(latestRated) }
  }

  /**
   * Resets a prisoner's current rating to "No rating" (R-01 readmission after release).
   *
   * Returns whether a rating was actually cleared. Most admissions find the prisoner already at "No rating"
   * and change nothing, and the caller uses this to keep those silent — announcing a rating change that did
   * not happen is the same mistake as publishing for an unrated draft.
   */
  fun resetToNoRating(prisonerNumber: String, updatedBy: String?): Boolean {
    val hadRating = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)?.rating != null
    upsert(prisonerNumber, updatedBy, CsraRatingSetReason.NO_RATING_ON_READMISSION) {
      rating = null
      provisional = false
      ratingStage = null
      assessmentType = null
      ratingDate = null
      setByReviewId = null
    }
    return hadRating
  }

  /**
   * Sets the prisoner's next review date to the one their latest final-rated review set, or removes it when
   * they have no such review.
   *
   * Only a final rating counts. An interim or provisional stage leaves the previous review's date standing
   * until the new review is completed, as it always has; and a review superseded by a readmission no longer
   * counts, so the old date goes once anything refreshes the prisoner rather than lingering on the
   * "high risk due for review" worklist against a new provisional rating. The row is left untouched when
   * nothing has changed, so a redelivered merge or move does not bump `updated_at`.
   */
  private fun refreshNextReview(prisonerNumber: String, latestFinal: CsraReviewEntity?, updatedBy: String?) {
    val existing = csraNextReviewRepository.findByPrisonerNumber(prisonerNumber)
    if (latestFinal == null) {
      existing?.let { csraNextReviewRepository.delete(it) }
      return
    }
    val reviewId = latestFinal.id!!
    val date = latestFinal.nextReviewDateOrNomis { csraReviewNomisRepository.findByCsraReviewId(reviewId) }
    if (existing != null && existing.setByReviewId == reviewId && existing.nextReviewDate == date) return

    val entity = existing ?: CsraNextReviewEntity(
      prisonerNumber = prisonerNumber,
      setByReviewId = reviewId,
      updatedAt = LocalDateTime.now(clock),
    )
    entity.nextReviewDate = date
    entity.setByReviewId = reviewId
    entity.updatedAt = LocalDateTime.now(clock)
    entity.updatedBy = updatedBy
    csraNextReviewRepository.save(entity)
  }

  private fun CsraCurrentRatingEntity.applyFrom(review: CsraReviewEntity) {
    rating = review.finalResult ?: review.interimResult
    provisional = review.finalResult == null && review.interimResult != null
    ratingStage = ratingStageFor(review.type, review.finalResult, review.interimResult)
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
