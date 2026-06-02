package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraSyncRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.NomisCsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import java.util.UUID

/**
 * Receives CSRA reviews migrated and synchronised from the legacy NOMIS system (via
 * hmpps-prisoner-from-nomis-migration).
 *
 * Persistence is not yet implemented: a fresh DPS id is generated per review and returned so the
 * migration service can record its NOMIS-to-DPS mapping. Storing the reviews and determining real
 * created/updated state will be added in the next step.
 */
@Service
class CsraMigrationSyncService(
  private val telemetryClient: TelemetryClient,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun migrate(prisonerNumber: String, reviews: List<NomisCsraReview>): List<CsraReview> = reviews.map { review ->
    CsraReview(
      id = UUID.randomUUID(),
    )
  }.also { results ->
    log.info("Migrated {} CSRA review(s) for {}", results.size, prisonerNumber)
    telemetryClient.trackEvent(
      "csra-migrated",
      mapOf("prisonerNumber" to prisonerNumber, "csraCount" to results.size.toString()),
      null,
    )
  }

  fun sync(prisonerNumber: String, request: CsraSyncRequest): SyncResult {
    val created = request.csraReviewId == null
    val csraReviewId = request.csraReviewId ?: UUID.randomUUID()
    log.info("Synchronised CSRA review {} for {} (created={})", csraReviewId, prisonerNumber, created)
    telemetryClient.trackEvent(
      "csra-synchronised",
      mapOf(
        "prisonerNumber" to prisonerNumber,
        "csraReviewId" to csraReviewId.toString(),
        "created" to created.toString(),
      ),
      null,
    )
    return SyncResult(csraReviewId = csraReviewId, created = created)
  }
}
