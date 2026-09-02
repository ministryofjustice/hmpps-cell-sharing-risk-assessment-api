package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * Reacts to prisoner movements (driven by the `prison-offender-events.prisoner.received` event) by
 * tidying up in-progress CSRA work, per rules R-01/R-02/R-04/R-05 (see the admission-event rules).
 *
 * On any admission that ends the prisoner's in-progress work at the sending establishment, an in-progress
 * review that already has a provisional/interim rating is **closed** (it stops being in progress but its
 * rating stands), and one with no rating yet is **archived** (retained but hidden). Both are naturally
 * idempotent: a redelivered event finds no in-progress review and does nothing.
 *
 * The two paths differ in what they record as the closure reason: a readmission after release is not a
 * transfer, and the record has to say so.
 */
@Service
@Transactional
class CsraMovementService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  /**
   * Readmission after a period of release (R-01): close/archive any in-progress review and reset the
   * prisoner's current CSRA rating to "No rating" (a fresh period of custody starts with no rating).
   */
  fun handleReadmission(prisonerNumber: String, prisonId: String?) {
    closeOrArchiveInProgress(prisonerNumber, prisonId, CsraClosureReason.NOT_COMPLETED_PRISONER_RELEASE)
    // Clearing a rating changes the prisoner's CSRA just as saving one does, so it is announced the same
    // way — otherwise a consumer (notably the DPS -> NOMIS sync) keeps the pre-release rating forever.
    // Only when something was actually cleared: most admissions find the prisoner already at "No rating".
    if (csraCurrentRatingService.resetToNoRating(prisonerNumber, SYSTEM_USERNAME)) {
      eventPublishAndAuditService.publishRatingCleared(
        prisonerNumber = prisonerNumber,
        auditData = mapOf(
          "prisonerNumber" to prisonerNumber,
          "prisonId" to prisonId,
          "reason" to CsraRatingSetReason.NO_RATING_ON_READMISSION.name,
        ),
      )
    }
  }

  /**
   * Transfer to another establishment with no release between (R-02): close/archive any in-progress review.
   *
   * Deliberately publishes nothing. Closing a rated review leaves its rating standing (R-06), and archiving
   * an unrated one removes a draft no consumer ever saw — either way the prisoner's current CSRA is
   * unchanged, so there is nothing to announce.
   */
  fun handleTransfer(prisonerNumber: String, prisonId: String?) {
    closeOrArchiveInProgress(prisonerNumber, prisonId, CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER)
  }

  private fun closeOrArchiveInProgress(prisonerNumber: String, prisonId: String?, reason: CsraClosureReason) {
    val inProgress = csraReviewRepository.findAllByPrisonerNumberAndStatus(prisonerNumber, CsraReviewStatus.IN_PROGRESS)
    inProgress.forEach { review ->
      // The review's own prisonId is deliberately left alone: it records where the assessment happened, not
      // where the prisoner has turned up. Stamping the receiving prison here would attribute a Leeds
      // assessment to Brixton in Brixton's history filter and rating summary. The review drops off both
      // worklists on status alone, so there is nothing to gain by moving it.
      val outcome = if (review.interimResult != null) CsraReviewStatus.CLOSED else CsraReviewStatus.ARCHIVED
      review.status = outcome
      review.closureReason = reason
      review.closedAt = LocalDateTime.now(clock)
      review.closedBy = SYSTEM_USERNAME
      csraReviewRepository.save(review)
      recordClosure(review, prisonId, reason, outcome)
    }
  }

  // R-05: record close/archive events so the team can measure how often in-progress work is disrupted.
  private fun recordClosure(review: CsraReviewEntity, prisonId: String?, reason: CsraClosureReason, outcome: CsraReviewStatus) {
    // Derived from the reason rather than passed separately, so the label and the stored reason cannot
    // drift. The existing values are kept because App Insights queries filter on them.
    val movement = when (reason) {
      CsraClosureReason.NOT_COMPLETED_PRISONER_RELEASE -> "readmission"
      CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER -> "transfer"
    }
    log.info("CSRA review {} {} on {} for {}", review.id, outcome, movement, review.prisonerNumber)
    telemetryClient.trackEvent(
      "csra-in-progress-closed-on-admission",
      mapOf(
        "prisonerNumber" to review.prisonerNumber,
        "reviewId" to review.id.toString(),
        "prisonId" to (prisonId ?: ""),
        "movement" to movement,
        "outcome" to outcome.name,
        "reason" to reason.name,
      ),
      null,
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(CsraMovementService::class.java)
  }
}
