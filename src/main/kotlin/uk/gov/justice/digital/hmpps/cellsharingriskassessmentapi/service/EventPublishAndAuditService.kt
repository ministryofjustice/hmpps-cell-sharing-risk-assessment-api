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
import java.util.UUID

@Service
class EventPublishAndAuditService(
  private val snsService: SnsService,
  private val auditService: AuditService,
  private val activeAgenciesService: ActiveAgenciesService,
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
   *
   * A DPS rating for a prison not switched on for CSRA is also audited but never published (MAPA-429) —
   * see [isHeldBackForRollout]. The prison checked is the review's, which the write journeys keep in step
   * with the prison on the request the rollout gate checked.
   */
  fun publishEvent(
    eventType: CSRADomainEventType,
    csraReview: CsraReview,
    auditData: Any? = null,
    source: InformationSource = InformationSource.DPS,
  ) {
    // Decided now, inside the caller's transaction, not after commit. An unrated review is never published
    // anyway, so it costs no read.
    val heldBack = csraReview.isRated() && isHeldBackForRollout(source, csraReview.prisonId)
    afterCommit { doPublishEvent(eventType, csraReview, auditData, source, heldBack) }
  }

  /**
   * Announces that a prisoner's current CSRA rating was cleared (R-01, readmission after a period of
   * release). Unlike every other rating change there is no review behind it — the projection is reset, not
   * derived from a record — so [AdditionalInformation.id] is null and a consumer reads the new state from
   * `GET /csra-review/prisoner/{prisonerNumber}/current-rating`.
   *
   * Only called when a rating was actually cleared: an admission that finds the prisoner already at "No
   * rating" publishes nothing, for the same reason an unrated draft does.
   *
   * [prisonId] is the prison the prisoner was received into. This is the event most likely to reach NOMIS
   * for a prison still using it (MAPA-429): a readmission happens whatever the prison's rollout state, and
   * NOMIS keeps the previous CSRA when a prisoner returns on an old booking, so the reset is audited but
   * not published unless that prison is switched on.
   */
  fun publishRatingCleared(prisonerNumber: String, prisonId: String?, auditData: Any) {
    val heldBack = isHeldBackForRollout(InformationSource.DPS, prisonId)
    afterCommit {
      if (heldBack) {
        suppressedPrisonNotActive(CSRADomainEventType.CSRA_AMENDED, null, prisonerNumber, prisonId, InformationSource.DPS)
      } else {
        snsService.publishDomainEvent(
          eventType = CSRADomainEventType.CSRA_AMENDED,
          description = CSRADomainEventType.CSRA_AMENDED.description,
          occurredAt = LocalDateTime.now(clock),
          additionalInformation = AdditionalInformation(
            id = null,
            nomsNumber = prisonerNumber,
            source = InformationSource.DPS,
          ),
        )
      }
      auditEvent(
        auditType = CSRADomainEventType.CSRA_AMENDED.auditType,
        id = prisonerNumber,
        auditData = auditData,
      )
    }
  }

  /**
   * Announces that a NOMIS prisoner-number merge moved a prisoner's CSRA data onto [prisonerNumber].
   *
   * Two rules are load-bearing here.
   *
   * **Only the retained number is announced.** Publishing against [removedNomsNumber] would tell consumers
   * to re-read a prisoner number NOMIS has deleted; the read would correctly answer "No rating", and a
   * consumer that cached it would have recorded "this person has no CSRA" against a number that no longer
   * exists. Instead, the retired number rides along on the retained number's event, so a consumer holding
   * data under the old key learns what to re-key from an event it is certain to receive.
   *
   * **Nothing is published unless the rating actually changed** ([ratingChanged]) — the same principle as
   * the unrated-draft suppression and the silent no-op readmission. The consequence to be aware of: a
   * merge that leaves the survivor's rating untouched still moves their history, and no consumer is told.
   * That is accepted because every consumer of CSRA also consumes `prison-offender-events.prisoner.merged`
   * and re-keys off that. The audit event is unconditional either way, so the repoint is always recorded.
   *
   * [InformationSource.NOMIS] because NOMIS has already re-parented the assessments — a sync service must
   * not treat this as a DPS-side edit and push it back.
   */
  fun publishPrisonerNumberMerged(
    prisonerNumber: String,
    removedNomsNumber: String,
    ratingChanged: Boolean,
    auditData: Any,
  ) = afterCommit {
    if (ratingChanged) {
      snsService.publishDomainEvent(
        eventType = CSRADomainEventType.CSRA_AMENDED,
        description = CSRADomainEventType.CSRA_AMENDED.description,
        occurredAt = LocalDateTime.now(clock),
        additionalInformation = AdditionalInformation(
          // No single review produced this: the rating was re-derived across two merged histories, so the
          // consumer re-reads `current-rating` rather than following an id.
          id = null,
          nomsNumber = prisonerNumber,
          source = InformationSource.NOMIS,
          removedNomsNumber = removedNomsNumber,
        ),
      )
    }
    auditEvent(
      auditType = AuditType.PRISONER_NUMBER_MERGE,
      id = prisonerNumber,
      auditData = auditData,
    )
  }

  fun publishReviewsMoved(
    prisonerNumber: String,
    ratingChanged: Boolean,
    auditData: Any,
  ) = afterCommit {
    if (ratingChanged) {
      snsService.publishDomainEvent(
        eventType = CSRADomainEventType.CSRA_AMENDED,
        description = CSRADomainEventType.CSRA_AMENDED.description,
        occurredAt = LocalDateTime.now(clock),
        additionalInformation = AdditionalInformation(
          // No single review produced this: the rating was re-derived across two changed histories, so the
          // consumer re-reads `current-rating` rather than following an id.
          id = null,
          nomsNumber = prisonerNumber,
          source = InformationSource.NOMIS,
        ),
      )
    }
    auditEvent(
      auditType = AuditType.CSRAS_MOVED,
      id = prisonerNumber,
      auditData = auditData,
    )
  }

  private fun doPublishEvent(
    eventType: CSRADomainEventType,
    csraReview: CsraReview,
    auditData: Any?,
    source: InformationSource,
    heldBack: Boolean,
  ) {
    if (!csraReview.isRated()) {
      suppressed(eventType, csraReview, source)
    } else if (heldBack) {
      suppressedPrisonNotActive(eventType, csraReview.id, csraReview.prisonerNumber, csraReview.prisonId, source)
    } else {
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

  /**
   * Whether an event must be held back because its prison is not switched on for CSRA (MAPA-429).
   *
   * While a prison still records CSRAs in NOMIS, NOMIS is the correct record there and nothing DPS does may
   * change it. The NOMIS update service acts only on [InformationSource.DPS] events, so only those are held
   * back. [InformationSource.NOMIS] events describe a change NOMIS has already made and are published
   * whatever the prison's state; that also keeps the merge and booking-move events, which carry no prison,
   * outside this rule. A DPS event with no prison is held back, as nothing shows its prison is switched on.
   *
   * Held-back events are not stored, so switching a prison on does not send them later.
   */
  private fun isHeldBackForRollout(source: InformationSource, prisonId: String?): Boolean = source == InformationSource.DPS && (prisonId == null || !activeAgenciesService.isActive(prisonId))

  private fun suppressedPrisonNotActive(
    eventType: CSRADomainEventType,
    csraReviewId: UUID?,
    prisonerNumber: String,
    prisonId: String?,
    source: InformationSource,
  ) {
    log.info("Suppressed {} for {}: prison {} is not switched on for CSRA", eventType.value, prisonerNumber, prisonId)
    telemetryClient.trackEvent(
      "csra-event-suppressed-prison-not-active",
      mapOf(
        "eventType" to eventType.value,
        "csraReviewId" to csraReviewId?.toString().orEmpty(),
        "prisonerNumber" to prisonerNumber,
        "prisonId" to prisonId.orEmpty(),
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
