package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import java.time.Clock
import java.time.LocalDateTime

@Service
class EventPublishAndAuditService(
  private val snsService: SnsService,
  private val auditService: AuditService,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {
  /**
   * Publishes a CSRA domain event and audits the change.
   *
   * A CSRA with no interim or final result is still a draft: the prisoner's CSRA has not changed and
   * nothing was ever recorded in NOMIS, so an unrated record must be able to come and go (be started,
   * amended, cancelled) without any consumer seeing it. Those changes are **audited but never published**.
   *
   * [source] tells consumers where the change originated. The NOMIS sync service must ignore
   * [InformationSource.NOMIS] events — they are the echo of a change it made itself, and acting on them
   * would loop NOMIS -> DPS -> NOMIS forever.
   */
  fun publishEvent(
    eventType: CSRADomainEventType,
    csraReview: CsraReview,
    auditData: Any? = null,
    source: InformationSource = InformationSource.DPS,
  ) = afterCommit { doPublishEvent(eventType, csraReview, auditData, source) }

  private fun doPublishEvent(
    eventType: CSRADomainEventType,
    csraReview: CsraReview,
    auditData: Any?,
    source: InformationSource,
  ) {
    if (csraReview.isRated()) {
      snsService.publishDomainEvent(
        eventType = eventType,
        description = eventType.description,
        occurredAt = LocalDateTime.now(clock),
        additionalInformation = AdditionalInformation(
          id = csraReview.id,
          nomsNumber = csraReview.prisonerNumber,
          source = source,
        ),
      )
    } else {
      suppressed(eventType, csraReview, source)
    }

    auditData?.let {
      auditEvent(
        auditType = eventType.auditType,
        id = csraReview.id.toString(),
        auditData = it,
        source = source,
      )
    }
  }

  fun auditEvent(
    auditType: AuditType,
    id: String,
    auditData: Any,
    source: InformationSource = InformationSource.DPS,
  ) {
    auditService.sendMessage(
      auditType = auditType,
      id = id,
      details = auditData,
    )
  }

  private fun suppressed(eventType: CSRADomainEventType, csraReview: CsraReview, source: InformationSource) {
    log.info("Suppressed {} for unrated CSRA {}", eventType.value, csraReview.id)
    telemetryClient.trackEvent(
      "csra-event-suppressed-no-rating",
      mapOf(
        "eventType" to eventType.value,
        "csraReviewId" to csraReview.id.toString(),
        "prisonerNumber" to csraReview.prisonerNumber,
        "source" to source.name,
      ),
      null,
    )
  }

  private fun CsraReview.isRated() = interimResult != null || finalResult != null

  /**
   * Defers [block] until the surrounding transaction commits, so a write that is later rolled back never
   * announces itself. Runs immediately when there is no transaction in progress.
   */
  private fun afterCommit(block: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      block()
      return
    }
    TransactionSynchronizationManager.registerSynchronization(
      object : TransactionSynchronization {
        override fun afterCommit() = block()
      },
    )
  }

  private companion object {
    val log: Logger = LoggerFactory.getLogger(EventPublishAndAuditService::class.java)
  }
}

enum class InformationSource {
  DPS,
  NOMIS,
}
