package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/**
 * Repoints a specific, known set of CSRA reviews onto the correct prisoner number — a manual data fix for
 * reviews that were recorded against the wrong prisoner number (e.g. a NOMIS booking.moved event), as opposed
 * to [CsraMergeService], which reacts to a NOMIS merge event and moves *everything* held against a retired
 * number.
 *
 * The scope here is deliberately narrower than a merge in two ways.
 *
 * The named reviews are moved unconditionally: there is no supersession watermark to apply, because these
 * are not two prisoner numbers for the same custodial history being reconciled — they are rows that were
 * simply filed under the wrong person and are now being corrected onto the right one.
 *
 * The `csra_current_rating` and `csra_next_review` projections are only touched where they are actually
 * changed by the move of the corrected reviews. A prisoner number that loses one of the named
 * reviews may still legitimately hold other, unrelated reviews, so — unlike a merge, where the retired
 * number ceases to exist and its projections are simply deleted — the prisoner numbers touched by the
 * correction (the reviews' original owner as well as the correct one) have their current-rating projections
 * recomputed from whatever reviews they are left with, rather than assumed empty.
 */
@Service
@Transactional
class CsraMoveService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  fun handleBookingMoved(reviewIds: List<UUID>, fromPrisonerNumber: String, toPrisonerNumber: String) {
    if (fromPrisonerNumber == toPrisonerNumber) {
      throw ValidationException("Cannot move reviews from $fromPrisonerNumber to itself")
    }
    val reviews = csraReviewRepository.findAllById(reviewIds)

    // The ids that actually need moving — either because they don't exist, or because a redelivery of an
    // already-handled move finds them already sitting under the correct number.
    val reviewsToMove = reviews.filter { it.prisonerNumber == fromPrisonerNumber }
    if (reviewsToMove.isEmpty()) {
      log.info(
        "Review move from {} to {} touched no reviews for ids {}",
        fromPrisonerNumber,
        toPrisonerNumber,
        reviewIds,
      )
      telemetryClient.trackEvent(
        "csra-reviews-moved-no-op",
        moveProperties(fromPrisonerNumber, toPrisonerNumber, reviewIds),
        null,
      )
      return
    }

    val fromBefore = csraCurrentRatingRepository.findByPrisonerNumber(fromPrisonerNumber)?.snapshot()
    val toBefore = csraCurrentRatingRepository.findByPrisonerNumber(toPrisonerNumber)?.snapshot()

    repointReviews(reviewsToMove, toPrisonerNumber)
    reconcileNextReviews(reviewsToMove.map { it.id!! }, fromPrisonerNumber, toPrisonerNumber)

    // Recompute every prisoner number this move could have changed the current-rating and next-review projection
    // for: the reviews' original owner, who may have lost the review that set their rating, and the
    // correct prisoner, who gained it.
    csraCurrentRatingService.refreshFromReviews(fromPrisonerNumber, SYSTEM_USERNAME)
    csraCurrentRatingService.refreshFromReviews(toPrisonerNumber, SYSTEM_USERNAME)

    val fromAfter = csraCurrentRatingRepository.findByPrisonerNumber(fromPrisonerNumber)?.snapshot()
    val toAfter = csraCurrentRatingRepository.findByPrisonerNumber(toPrisonerNumber)?.snapshot()
    val fromRatingChanged = fromBefore?.takeIf { it.rating != null } != fromAfter?.takeIf { it.rating != null }
    val toRatingChanged = toBefore?.takeIf { it.rating != null } != toAfter?.takeIf { it.rating != null }

    val movedReviewIds = reviewsToMove.map { it.id as UUID }
    eventPublishAndAuditService.publishReviewsMoved(
      prisonerNumber = fromPrisonerNumber,
      ratingChanged = fromRatingChanged,
      auditData = MoveAuditDetail(
        toPrisonerNumber = toPrisonerNumber,
        fromPrisonerNumber = fromPrisonerNumber,
        reviewIds = movedReviewIds,
        ratingChanged = fromRatingChanged,
      ),
    )
    eventPublishAndAuditService.publishReviewsMoved(
      prisonerNumber = toPrisonerNumber,
      ratingChanged = toRatingChanged,
      auditData = MoveAuditDetail(
        toPrisonerNumber = toPrisonerNumber,
        fromPrisonerNumber = fromPrisonerNumber,
        reviewIds = movedReviewIds,
        ratingChanged = toRatingChanged,
      ),
    )

    log.info(
      "Review move from {} (rating changed: {}) onto {} (rating changed: {}) moved {} review(s)",
      fromPrisonerNumber,
      fromRatingChanged,
      toPrisonerNumber,
      toRatingChanged,
      reviewsToMove.size,
    )
    telemetryClient.trackEvent(
      "csra-reviews-moved",
      moveProperties(fromPrisonerNumber, toPrisonerNumber, reviewIds) + mapOf(
        "reviewsMoved" to reviewsToMove.size.toString(),
        "fromRatingChanged" to fromRatingChanged.toString(),
        "toRatingChanged" to toRatingChanged.toString(),
      ),
      null,
    )
  }

  private fun repointReviews(reviews: List<CsraReviewEntity>, correctPrisonerNumber: String) {
    reviews.forEach { it.prisonerNumber = correctPrisonerNumber }
    csraReviewRepository.saveAllAndFlush(reviews)
  }

  private fun reconcileNextReviews(
    reviewIds: List<UUID>,
    fromPrisonerNumber: String,
    toPrisonerNumber: String,
  ) {
    val fromNextReview = csraNextReviewRepository.findByPrisonerNumber(fromPrisonerNumber)
    var toNextReview = csraNextReviewRepository.findByPrisonerNumber(toPrisonerNumber)

    val movingReviews = csraReviewRepository.findAllById(reviewIds)
    val latestOfFromReviews = csraReviewRepository.findFirstByPrisonerNumberAndStatusOrderByAssessmentDateDescIdDesc(
      fromPrisonerNumber,
      CsraReviewStatus.COMPLETE,
    )
    val toReviews = csraReviewRepository.findAllByPrisonerNumberAndStatus(toPrisonerNumber, CsraReviewStatus.COMPLETE)

    val latestOfMovingReviews = movingReviews.filter { it.status == CsraReviewStatus.COMPLETE }
      .maxWithOrNull(compareBy(CsraReviewEntity::assessmentDate).thenBy(CsraReviewEntity::id))

    val latestOfToReviews = toReviews.filter { it.status == CsraReviewStatus.COMPLETE && it.id !in reviewIds }
      .maxWithOrNull(compareBy(CsraReviewEntity::assessmentDate).thenBy(CsraReviewEntity::id))

    if (latestOfMovingReviews != null && compareValuesBy(
        latestOfMovingReviews,
        latestOfToReviews,
        { it.assessmentDate },
        { it.id },
      ) > 0
    ) {
      // ^^ true if first is later than second
      if (toNextReview == null) {
        toNextReview = csraNextReviewRepository.saveAndFlush(
          CsraNextReviewEntity(
            prisonerNumber = toPrisonerNumber,
            nextReviewDate = latestOfMovingReviews.finalResultDate?.plusMonths(6), // TODO not sure about this!!
            setByReviewId = latestOfMovingReviews.id!!,
            updatedAt = LocalDateTime.now(clock),
            updatedBy = SYSTEM_USERNAME,
          ),
        )
      }
    } else {

      if (fromNextReview != null && latestOfFromReviews != null && fromNextReview.setByReviewId in reviewIds) {
        if (toNextReview == null) {
          toNextReview = csraNextReviewRepository.saveAndFlush(
            CsraNextReviewEntity(
              prisonerNumber = toPrisonerNumber,
              nextReviewDate = latestOfMovingReviews.finalResultDate?.plusMonths(6), // TODO not sure about this!!
              setByReviewId = latestOfMovingReviews.id!!,
              updatedAt = LocalDateTime.now(clock),
              updatedBy = SYSTEM_USERNAME,
            ),
          )
        } else {
          // latestOfMovingReviews is the latest of the 'from' reviews
          toNextReview.nextReviewDate = fromNextReview.nextReviewDate
          toNextReview.setByReviewId = fromNextReview.setByReviewId
          toNextReview.updatedAt = LocalDateTime.now(clock)
          toNextReview.updatedBy = SYSTEM_USERNAME
          csraNextReviewRepository.saveAndFlush(toNextReview)
        }
      } else {
        if (toNextReview == null) {
          toNextReview = csraNextReviewRepository.saveAndFlush(
            CsraNextReviewEntity(
              prisonerNumber = toPrisonerNumber,
              nextReviewDate = latestOfMovingReviews.finalResultDate?.plusMonths(6), // TODO not sure about this!!
              setByReviewId = latestOfMovingReviews.id!!,
              updatedAt = LocalDateTime.now(clock),
              updatedBy = SYSTEM_USERNAME,
            ),
          )
        } else {
          toNextReview.nextReviewDate = latestOfMovingReviews.finalResultDate?.plusMonths(12) // TODO not sure about this!!
          toNextReview.setByReviewId = latestOfMovingReviews.id!!
          toNextReview.updatedAt = LocalDateTime.now(clock)
          toNextReview.updatedBy = SYSTEM_USERNAME
          csraNextReviewRepository.saveAndFlush(toNextReview)
        }
      }
    }

    if (fromNextReview != null && latestOfFromReviews != null && fromNextReview.setByReviewId in reviewIds) {
      fromNextReview.nextReviewDate = latestOfFromReviews.finalResultDate?.plusMonths(12) // TODO not sure about this!!
      fromNextReview.setByReviewId = latestOfFromReviews.id!!
      fromNextReview.updatedAt = LocalDateTime.now(clock)
      fromNextReview.updatedBy = SYSTEM_USERNAME
      csraNextReviewRepository.saveAndFlush(fromNextReview)
    }
  }

  private fun moveProperties(fromPrisonerNumber: String, toPrisonerNumber: String, reviewIds: List<UUID>) = mapOf(
    "fromPrisonerNumber" to fromPrisonerNumber,
    "toPrisonerNumber" to toPrisonerNumber,
    "reviewIds" to reviewIds.joinToString(limit = 50),
  )

  private companion object {
    private val log = LoggerFactory.getLogger(CsraMoveService::class.java)
  }
}

data class MoveAuditDetail(
  val toPrisonerNumber: String,
  val fromPrisonerNumber: String,
  val reviewIds: List<UUID>,
  val ratingChanged: Boolean,
)
