package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Moves a prisoner's CSRA data when NOMIS merges two prisoner numbers into one.
 *
 * A merge (the NOMIS `emerge` screening) resolves the case where the same person is held under two
 * prisoner numbers — typically because reception booked a returning prisoner in under a new number. The
 * *oldest* number always survives; the newer one is deleted from NOMIS and never exists again. Everything
 * we hold against the retired number therefore has to move, or it is stranded against a number no read
 * endpoint will ever ask for.
 *
 * Three tables carry a prisoner number. `csra_review` has many rows per prisoner and no uniqueness
 * constraint, so those simply move. `csra_current_rating` and `csra_next_review` have one row per prisoner
 * behind a **unique index on `prisoner_number`**, so the retired number's rows are deleted and the
 * survivor's re-derived from the combined reviews rather than repointed.
 */
@Service
@Transactional
class CsraMergeService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {

  fun handleMerge(retained: String, removed: String) {
    val reviews = csraReviewRepository.findAllByPrisonerNumber(removed)
    val removedRating = csraCurrentRatingRepository.findByPrisonerNumber(removed)
    val removedNextReview = csraNextReviewRepository.findByPrisonerNumber(removed)

    // Nothing to move. This is the common case — most merges are of prisoners we hold no CSRA for — and
    // it is also every redelivery of an already-handled merge, since after the first run no row anywhere
    // carries the removed number. Returning here rather than falling through keeps redelivery genuinely
    // inert: no projection refresh, no bumped `setAt`, no event.
    if (reviews.isEmpty() && removedRating == null && removedNextReview == null) {
      log.info("Prisoner merge {} -> {} touched no CSRA data", removed, retained)
      telemetryClient.trackEvent(TELEMETRY_NO_OP, mergeProperties(removed, retained), null)
      return
    }

    val before = csraCurrentRatingRepository.findByPrisonerNumber(retained)?.snapshot()

    repointReviews(reviews, retained)
    reconcileProjections(removedRating, removedNextReview, retained)

    val after = csraCurrentRatingRepository.findByPrisonerNumber(retained)?.snapshot()
    val ratingChanged = before != after

    eventPublishAndAuditService.publishPrisonerNumberMerged(
      prisonerNumber = retained,
      removedNomsNumber = removed,
      ratingChanged = ratingChanged,
      auditData = MergeAuditDetail(
        retainedNomsNumber = retained,
        removedNomsNumber = removed,
        reviewsMoved = reviews.size,
        ratingChanged = ratingChanged,
      ),
    )

    log.info("Prisoner merge {} -> {} moved {} review(s), rating changed: {}", removed, retained, reviews.size, ratingChanged)
    telemetryClient.trackEvent(
      TELEMETRY_MERGE,
      mergeProperties(removed, retained) + mapOf(
        "reviewsMoved" to reviews.size.toString(),
        "ratingChanged" to ratingChanged.toString(),
      ),
      null,
    )
  }

  /**
   * Repoints the retired number's reviews, superseding any that predate the retained prisoner's current
   * period of custody.
   *
   * The supersession is the subtle part. [CsraReviewRepository.findRatedReviews] — which is what decides
   * the current rating — excludes superseded reviews, because a readmission after release resets the
   * rating to "No rating" (R-01) by stamping `supersededAt` on everything from the previous custody. The
   * retired number's reviews arrive *unsuperseded*, so repointing them blindly would hand
   * `findRatedReviews` a pre-release rating it would happily reinstate, silently undoing R-01 for the
   * merged prisoner.
   *
   * So: anything dated on or before the retained prisoner's newest already-superseded review belongs to a
   * closed period of custody and is superseded on the way in. Superseding *everything* from the retired
   * number would be wrong — the common real merge is reception creating a duplicate number at the current
   * admission, so those reviews are usually the live ones.
   */
  private fun repointReviews(reviews: List<CsraReviewEntity>, retained: String) {
    if (reviews.isEmpty()) return
    val now = LocalDateTime.now(clock)
    val watermark = supersessionWatermark(retained)
    reviews.forEach { review ->
      review.prisonerNumber = retained
      if (review.supersededAt == null && watermark != null && !review.assessmentDate.isAfter(watermark)) {
        review.supersededAt = now
      }
    }
    csraReviewRepository.saveAllAndFlush(reviews)
  }

  /** The assessment date of the retained prisoner's most recent already-superseded review, if any. */
  private fun supersessionWatermark(retained: String): LocalDate? = csraReviewRepository
    .findAllByPrisonerNumber(retained)
    .filter { it.supersededAt != null }
    .maxOfOrNull { it.assessmentDate }

  /**
   * Leaves the survivor's own projection rows alone and lets [CsraCurrentRatingService.refreshFromReviews]
   * upsert them from the combined review set: the latest rated, non-superseded, non-archived review sets
   * the rating and the latest final-rated one the next review date, whichever number either came in under.
   * Each review carries the date it set (SDIT-4297), so the retired number's next-review row holds nothing
   * that cannot be re-derived.
   *
   * Only the retired number's rows are deleted, and they are flushed before the refresh: both prisoners'
   * rows exist behind a unique index on `prisoner_number`, and deleting the survivor's too would leave the
   * delete and the re-insert racing within one transaction.
   */
  private fun reconcileProjections(removedRating: CsraCurrentRatingEntity?, removedNextReview: CsraNextReviewEntity?, retained: String) {
    removedRating?.let {
      csraCurrentRatingRepository.delete(it)
      csraCurrentRatingRepository.flush()
    }
    removedNextReview?.let {
      csraNextReviewRepository.delete(it)
      csraNextReviewRepository.flush()
    }
    csraCurrentRatingService.refreshFromReviews(retained, SYSTEM_USERNAME)
  }

  private fun mergeProperties(removed: String, retained: String) = mapOf(
    "NOMS-MERGE-FROM" to removed,
    "NOMS-MERGE-TO" to retained,
  )

  private companion object {
    private const val TELEMETRY_MERGE = "csra-merge"
    private const val TELEMETRY_NO_OP = "csra-merge-no-op"
    private val log = LoggerFactory.getLogger(CsraMergeService::class.java)
  }
}

internal fun CsraCurrentRatingEntity.snapshot() = RatingSnapshot(rating, provisional, ratingDate, setByReviewId)

/**
 * The parts of a prisoner's current rating a consumer can observe. Compared before and after a merge to
 * decide whether the merge is worth announcing. [setByReviewId] is included deliberately: the same rating
 * arriving from a different review is a real change to anyone who follows the id.
 */
internal data class RatingSnapshot(
  val rating: CsraResult?,
  val provisional: Boolean,
  val ratingDate: LocalDate?,
  val setByReviewId: UUID?,
)

/** What is recorded on the HMPPS audit queue for a merge. */
data class MergeAuditDetail(
  val retainedNomsNumber: String,
  val removedNomsNumber: String,
  val reviewsMoved: Int,
  val ratingChanged: Boolean,
)
