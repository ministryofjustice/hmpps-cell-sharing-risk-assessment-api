package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
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
 */
@Service
@Transactional
class CsraMovementService(
  private val csraReviewRepository: CsraReviewRepository,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  /**
   * Readmission after a period of release (R-01): close/archive any in-progress review.
   *
   * TODO (PR-2): also reset the prisoner's current CSRA rating to "No rating" via the csra_current_rating
   * projection. Until then this behaves the same as a transfer, minus the reset.
   */
  fun handleReadmission(prisonerNumber: String, prisonId: String?) {
    closeOrArchiveInProgress(prisonerNumber, prisonId, "readmission")
  }

  /** Transfer to another establishment with no release between (R-02): close/archive any in-progress review. */
  fun handleTransfer(prisonerNumber: String, prisonId: String?) {
    closeOrArchiveInProgress(prisonerNumber, prisonId, "transfer")
  }

  private fun closeOrArchiveInProgress(prisonerNumber: String, prisonId: String?, movement: String) {
    val inProgress = csraReviewRepository.findAllByPrisonerNumberAndStatus(prisonerNumber, CsraReviewStatus.IN_PROGRESS)
    inProgress.forEach { review ->
      val outcome = if (review.interimResult != null) CsraReviewStatus.CLOSED else CsraReviewStatus.ARCHIVED
      review.status = outcome
      review.closureReason = CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER
      review.closedAt = LocalDateTime.now(clock)
      review.closedBy = SYSTEM_USERNAME
      csraReviewRepository.save(review)
      recordClosure(review, prisonId, movement, outcome)
    }
  }

  // R-05: record close/archive events so the team can measure how often in-progress work is disrupted.
  private fun recordClosure(review: CsraReviewEntity, prisonId: String?, movement: String, outcome: CsraReviewStatus) {
    log.info("CSRA review {} {} on {} for {}", review.id, outcome, movement, review.prisonerNumber)
    telemetryClient.trackEvent(
      "csra-in-progress-closed-on-admission",
      mapOf(
        "prisonerNumber" to review.prisonerNumber,
        "reviewId" to review.id.toString(),
        "prisonId" to (prisonId ?: ""),
        "movement" to movement,
        "outcome" to outcome.name,
        "reason" to CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER.name,
      ),
      null,
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(CsraMovementService::class.java)
  }
}
