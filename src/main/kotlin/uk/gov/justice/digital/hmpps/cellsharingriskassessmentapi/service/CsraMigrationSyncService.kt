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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.toNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.updateFromNomis
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
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
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val writeSupport: CsraWriteSupport,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun migrate(prisonerNumber: String, reviews: List<NomisCsraReview>): List<CsraMigrationResponse> {
    val saved = reviews.map { review ->
      val savedReview = csraReviewRepository.save(review.toNewCsraReview(prisonerNumber))
      csraReviewNomisRepository.save(review.toNomisEntity(savedReview, clock))
      review to savedReview
    }

    // Recompute the prisoner's current rating and next review date from the freshly loaded reviews.
    csraCurrentRatingService.refreshFromReviews(prisonerNumber)

    log.info("Migrated {} CSRA review(s) for {}", saved.size, prisonerNumber)
    telemetryClient.trackEvent(
      "csra-migrated",
      mapOf("prisonerNumber" to prisonerNumber, "csraCount" to saved.size.toString()),
      null,
    )
    return saved.map { (review, savedReview) -> CsraMigrationResponse(savedReview.id!!, review.bookingId, review.nomisSequence) }
  }

  fun sync(prisonerNumber: String, request: CsraSyncRequest): SyncResult {
    val csraReviewId = request.csraReviewId
    val created = csraReviewId == null
    val review = if (csraReviewId == null) {
      val saved = csraReviewRepository.save(request.review.toNewCsraReview(prisonerNumber))
      csraReviewNomisRepository.save(request.review.toNomisEntity(saved, clock))
      saved
    } else {
      val existing = csraReviewRepository.findByIdOrNull(csraReviewId)
        ?: throw CsraReviewNotFoundException(csraReviewId.toString())
      // A closed or archived review is not writable from here either. updateFromNomis forces the row
      // back to COMPLETE, which would resurrect a record a movement has ended. NOMIS-sourced rows are
      // always COMPLETE, so in practice this only fires after a data fix.
      writeSupport.rejectIfNotWritable(existing)
      existing.updateFromNomis(prisonerNumber, request.review, clock)
      val existingNomis = csraReviewNomisRepository.findByCsraReviewId(csraReviewId)
      if (existingNomis == null) {
        csraReviewNomisRepository.save(request.review.toNomisEntity(existing, clock))
      } else {
        existingNomis.updateFromNomis(request.review, clock)
      }
      existing
    }

    // Recompute the prisoner's current rating and next review date (the synced review may have gained or
    // changed a rating or date). Both are derived from the latest review, so an out-of-order sync of an
    // older review changes neither.
    csraCurrentRatingService.refreshFromReviews(prisonerNumber)

    // Announce the change so DPS consumers stay current. Stamped NOMIS so the sync service knows this is
    // the echo of its own write and must not push it back to NOMIS. Unrated reviews publish nothing.
    eventPublishAndAuditService.publishEvent(
      eventType = if (created) CSRADomainEventType.CSRA_CREATED else CSRADomainEventType.CSRA_AMENDED,
      csraReview = review.toDto(),
      auditData = review.toDto(),
      source = InformationSource.NOMIS,
    )

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
