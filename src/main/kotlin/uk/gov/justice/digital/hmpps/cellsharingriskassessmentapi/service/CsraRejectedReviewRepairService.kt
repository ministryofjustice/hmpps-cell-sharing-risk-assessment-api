package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository

/**
 * Puts right prisoners whose current rating or next review date was set by a NOMIS review rejected at approval
 * (MAPA-526). New ratings already leave rejected reviews out ([CsraCurrentRatingService.refreshFromReviews]); this
 * recalculates the prisoners rated before that change. 80 prisoners in production when written.
 *
 * Safe to run more than once: a prisoner already put right is no longer found, and a recalculation that changes
 * nothing publishes and audits nothing. Each prisoner is recalculated in its own transaction, so one failure does
 * not stop the rest.
 */
@Service
class CsraRejectedReviewRepairService(
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val transactionTemplate: TransactionTemplate,
  private val telemetryClient: TelemetryClient,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(CsraRejectedReviewRepairService::class.java)
  }

  fun repair(): RepairResult {
    val prisonerNumbers = csraReviewNomisRepository.findPrisonerNumbersRatedByRejectedReview()
    var changed = 0
    var failed = 0
    prisonerNumbers.forEach { prisonerNumber ->
      runCatching { transactionTemplate.execute { recalculate(prisonerNumber) } }
        .onSuccess { if (it == true) changed++ }
        .onFailure {
          failed++
          log.error("Failed to recalculate the CSRA rating for {}", prisonerNumber, it)
        }
    }
    val result = RepairResult(found = prisonerNumbers.size, changed = changed, failed = failed)
    log.info("Recalculated CSRA ratings set by rejected NOMIS reviews: {}", result)
    telemetryClient.trackEvent(
      "csra-rejected-review-repair",
      mapOf("found" to result.found.toString(), "changed" to result.changed.toString(), "failed" to result.failed.toString()),
      null,
    )
    return result
  }

  private fun recalculate(prisonerNumber: String): Boolean {
    val before = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)?.snapshot()
    csraCurrentRatingService.refreshFromReviews(prisonerNumber, SYSTEM_USERNAME)
    val after = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)?.snapshot()
    if (before == after) return false

    eventPublishAndAuditService.publishRatingRecalculated(
      prisonerNumber = prisonerNumber,
      auditData = RatingRecalculatedAuditDetail(
        prisonerNumber = prisonerNumber,
        reason = "Review that set the rating was rejected at approval in NOMIS",
        ratingBefore = before?.rating?.name,
        ratingAfter = after?.rating?.name,
        setByReviewIdBefore = before?.setByReviewId?.toString(),
        setByReviewIdAfter = after?.setByReviewId?.toString(),
      ),
    )
    return true
  }
}

data class RepairResult(val found: Int, val changed: Int, val failed: Int)

/** What is recorded on the HMPPS audit queue for a recalculated rating. */
data class RatingRecalculatedAuditDetail(
  val prisonerNumber: String,
  val reason: String,
  val ratingBefore: String?,
  val ratingAfter: String?,
  val setByReviewIdBefore: String?,
  val setByReviewIdAfter: String?,
)

/**
 * Runs [CsraRejectedReviewRepairService.repair] once at startup when `csra.repair-rejected-nomis-reviews` is true
 * (off by default; switched on in each environment's helm values for one release, then removed). A failure is
 * logged and does not stop the service starting.
 *
 * With several pods starting together two may recalculate the same prisoner at once. The second writes the same
 * values and may publish a duplicate `.amended` event, which is harmless: consumers re-read the current rating.
 */
@Component
@ConditionalOnProperty(name = ["csra.repair-rejected-nomis-reviews"], havingValue = "true")
class CsraRejectedReviewRepairRunner(
  private val csraRejectedReviewRepairService: CsraRejectedReviewRepairService,
) : ApplicationRunner {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(CsraRejectedReviewRepairRunner::class.java)
  }

  override fun run(args: ApplicationArguments) {
    runCatching { csraRejectedReviewRepairService.repair() }
      .onFailure { log.error("CSRA rejected review repair failed", it) }
  }
}
