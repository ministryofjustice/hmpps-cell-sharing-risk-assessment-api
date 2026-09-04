package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMergeService
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMovementService

/**
 * Consumes HMPPS domain events from the `csra` SQS queue (subscribed to the shared domain-events topic).
 *
 * Two event types matter:
 *
 * - **`prison-offender-events.prisoner.received`** — per the CSRA rules nothing happens at release; the
 *   tidy-up of in-progress work happens on the next admission, and the event's `reason` tells us whether
 *   it was a readmission after release (R-01) or a transfer between establishments (R-02). Returns from
 *   court or temporary absence (same establishment) are ignored.
 * - **`prison-offender-events.prisoner.merged`** — NOMIS has resolved two prisoner numbers for the same
 *   person into one. All CSRA data moves from the retired number to the retained one.
 *
 * This is deliberately the *only* `@SqsListener` on the `csra` queue. `@SqsListener` binds a message
 * listener container to a queue, so a second listener class naming the same queue would create a second
 * container polling it — SQS delivers each message to exactly one consumer, so roughly half of each event
 * type would land in the wrong handler and be silently dropped. One listener, dispatching on `eventType`.
 */
@Service
class PrisonerDomainEventListener(
  private val objectMapper: ObjectMapper,
  private val csraMovementService: CsraMovementService,
  private val csraMergeService: CsraMergeService,
  @param:Value("\${csra.process-movement-events:true}") private val processMovementEvents: Boolean,
  @param:Value("\${csra.process-merge-events:true}") private val processMergeEvents: Boolean,
) {

  @SqsListener("csra", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<PrisonerDomainEvent>(sqsMessage.message)
    when (event.eventType) {
      PRISONER_RECEIVED_EVENT_TYPE -> ifEnabled(processMovementEvents, event) { handlePrisonerReceived(event) }
      PRISONER_MERGED_EVENT_TYPE -> ifEnabled(processMergeEvents, event) { handlePrisonerMerged(event) }
      else -> log.debug("Ignoring domain event of type {}", event.eventType)
    }
  }

  /**
   * The two switches are independent on purpose. A merge repoints history and deletes projection rows;
   * a received event does not. Turning one off in an incident must not force the other off with it.
   */
  private fun ifEnabled(enabled: Boolean, event: PrisonerDomainEvent, handle: () -> Unit) {
    if (!enabled) {
      log.info("Processing disabled for {}; ignoring", event.eventType)
      return
    }
    handle()
  }

  private fun handlePrisonerReceived(event: PrisonerDomainEvent) {
    val info = event.additionalInformation
    val prisonerNumber = info?.nomsNumber
    val reason = info?.reason
    if (prisonerNumber.isNullOrBlank() || reason.isNullOrBlank()) {
      log.warn("Ignoring {} with missing prisoner number or reason", event.eventType)
      return
    }
    when (reason) {
      in READMISSION_REASONS -> csraMovementService.handleReadmission(prisonerNumber, info.prisonId)
      TRANSFERRED_REASON -> csraMovementService.handleTransfer(prisonerNumber, info.prisonId)
      // Returns from court/temporary absence (same establishment), post-merge admissions, etc. — no action.
      // POST_MERGE_ADMISSION stays a no-op deliberately: the merge itself is handled by the merged event
      // below, which carries both numbers; the admission that follows it tells us nothing extra.
      else -> log.debug("Ignoring {} with reason {}", event.eventType, reason)
    }
  }

  /**
   * A merge names both numbers. Anything malformed — a missing number, or a "merge" of a number into
   * itself — is logged and dropped rather than thrown: throwing would retry and eventually dead-letter a
   * message that will never become valid.
   */
  private fun handlePrisonerMerged(event: PrisonerDomainEvent) {
    val info = event.additionalInformation
    val retained = info?.nomsNumber
    val removed = info?.removedNomsNumber
    if (retained.isNullOrBlank() || removed.isNullOrBlank()) {
      log.warn("Ignoring {} with missing retained or removed prisoner number", event.eventType)
      return
    }
    if (retained == removed) {
      log.warn("Ignoring {} that merges {} into itself", event.eventType, retained)
      return
    }
    csraMergeService.handleMerge(retained = retained, removed = removed)
  }

  private companion object {
    private const val PRISONER_RECEIVED_EVENT_TYPE = "prison-offender-events.prisoner.received"
    private const val PRISONER_MERGED_EVENT_TYPE = "prison-offender-events.prisoner.merged"
    private const val TRANSFERRED_REASON = "TRANSFERRED"

    /** Admissions that follow a period out of prison (a new/return admission) — R-01. */
    private val READMISSION_REASONS = setOf("NEW_ADMISSION", "READMISSION", "READMISSION_SWITCH_BOOKING")
    private val log = LoggerFactory.getLogger(PrisonerDomainEventListener::class.java)
  }
}
