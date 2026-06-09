package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraMigrationResponse
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraSyncRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.NomisCsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.toNewCsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.updateFromNomis
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotFoundException
import java.time.Clock

/**
 * Receives CSRA reviews migrated and synchronised from the legacy NOMIS system (via
 * hmpps-prisoner-from-nomis-migration) and persists the core data.
 */
@Service
@Transactional
class CsraMigrationSyncService(
  private val csraReviewRepository: CsraReviewRepository,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun migrate(prisonerNumber: String, reviews: List<NomisCsraReview>): List<CsraMigrationResponse> = reviews
    .map {
      val saved = csraReviewRepository.save(it.toNewCsraReview(prisonerNumber))
      CsraMigrationResponse(saved.id!!, it.bookingId, it.nomisSequence)
    }
    .also { results ->
      log.info("Migrated {} CSRA review(s) for {}", results.size, prisonerNumber)
      telemetryClient.trackEvent(
        "csra-migrated",
        mapOf("prisonerNumber" to prisonerNumber, "csraCount" to results.size.toString()),
        null,
      )
    }

  fun sync(prisonerNumber: String, request: CsraSyncRequest): SyncResult {
    val csraReviewId = request.csraReviewId
    val created = csraReviewId == null
    val review = if (csraReviewId == null) {
      csraReviewRepository.save(request.review.toNewCsraReview(prisonerNumber))
    } else {
      val existing = csraReviewRepository.findByIdOrNull(csraReviewId)
        ?: throw CsraReviewNotFoundException(csraReviewId.toString())
      existing.updateFromNomis(prisonerNumber, request.review, clock)
      existing
    }
    log.info("Synchronised CSRA review {} for {} (created={})", review.id, prisonerNumber, created)
    telemetryClient.trackEvent(
      "csra-synchronised",
      mapOf(
        "prisonerNumber" to prisonerNumber,
        "csraReviewId" to review.id.toString(),
        "created" to created.toString(),
      ),
      null,
    )
    return SyncResult(csraReviewId = review.id!!, created = created)
  }
}
