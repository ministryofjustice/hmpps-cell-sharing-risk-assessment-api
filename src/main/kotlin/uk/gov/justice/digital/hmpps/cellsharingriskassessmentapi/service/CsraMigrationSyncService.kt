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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotFoundException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Receives CSRA reviews migrated and synchronised from the legacy NOMIS system (via
 * hmpps-prisoner-from-nomis-migration) and persists the core data.
 */
@Service
@Transactional
class CsraMigrationSyncService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun migrate(prisonerNumber: String, reviews: List<NomisCsraReview>): List<CsraMigrationResponse> {
    val saved = reviews.map { review ->
      val savedReview = csraReviewRepository.save(review.toNewCsraReview(prisonerNumber))
      csraReviewNomisRepository.save(review.toNomisEntity(savedReview))
      review to savedReview
    }

    // The prisoner's latest review carries the current next review date (later dates, then later inserts,
    // win). A migrate is an authoritative full load, so the latest in this batch is the current one.
    saved.reduceOrNull { latest, next -> if (next.first.assessmentDate >= latest.first.assessmentDate) next else latest }
      ?.let { (review, savedReview) -> upsertNextReview(prisonerNumber, review.nextReviewDate, savedReview.id!!, review.createdBy) }

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
      csraReviewNomisRepository.save(request.review.toNomisEntity(saved))
      saved
    } else {
      val existing = csraReviewRepository.findByIdOrNull(csraReviewId)
        ?: throw CsraReviewNotFoundException(csraReviewId.toString())
      existing.updateFromNomis(prisonerNumber, request.review, clock)
      val existingNomis = csraReviewNomisRepository.findByCsraReviewId(csraReviewId)
      if (existingNomis == null) {
        csraReviewNomisRepository.save(request.review.toNomisEntity(existing))
      } else {
        existingNomis.updateFromNomis(request.review)
      }
      existing
    }

    // Only the prisoner's latest review sets the current next review date, so an out-of-order sync of an
    // older review does not overwrite it.
    if (csraReviewRepository.findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber)?.id == review.id) {
      upsertNextReview(prisonerNumber, request.review.nextReviewDate, review.id!!, request.review.createdBy)
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

  /** Upserts the prisoner's single current next review date, stamping the review that set it. */
  private fun upsertNextReview(prisonerNumber: String, nextReviewDate: LocalDate?, reviewId: UUID, updatedBy: String?) {
    val existing = csraNextReviewRepository.findByPrisonerNumber(prisonerNumber)
    val entity = existing?.apply {
      this.nextReviewDate = nextReviewDate
      this.setByReviewId = reviewId
      this.updatedAt = LocalDateTime.now(clock)
      this.updatedBy = updatedBy
    } ?: CsraNextReviewEntity(
      prisonerNumber = prisonerNumber,
      nextReviewDate = nextReviewDate,
      setByReviewId = reviewId,
      updatedAt = LocalDateTime.now(clock),
      updatedBy = updatedBy,
    )
    csraNextReviewRepository.save(entity)
  }
}
