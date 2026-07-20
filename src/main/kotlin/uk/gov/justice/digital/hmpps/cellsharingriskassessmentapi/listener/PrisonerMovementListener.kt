package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.listener

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMovementService

/**
 * Consumes HMPPS prisoner-movement domain events from the `csra` SQS queue (subscribed to the shared
 * domain-events topic, filtered to `prison-offender-events.prisoner.received`).
 *
 * Only the *received* event is needed: per the CSRA rules nothing happens at release — the tidy-up of
 * in-progress work happens on the next admission, and the received event's `reason` tells us whether it
 * was a readmission after release (R-01) or a transfer between establishments (R-02). Returns from court
 * or temporary absence (same establishment) are ignored.
 */
@Service
class PrisonerMovementListener(
  private val objectMapper: ObjectMapper,
  private val csraMovementService: CsraMovementService,
  @param:Value("\${csra.process-movement-events:true}") private val processMovementEvents: Boolean,
) {

  @SqsListener("csra", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<PrisonerMovementEvent>(sqsMessage.message)
    if (event.eventType != PRISONER_RECEIVED_EVENT_TYPE) {
      log.debug("Ignoring domain event of type {}", event.eventType)
      return
    }
    if (!processMovementEvents) {
      log.info("Movement-event processing disabled; ignoring {}", event.eventType)
      return
    }
    handlePrisonerReceived(event)
  }

  private fun handlePrisonerReceived(event: PrisonerMovementEvent) {
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
      else -> log.debug("Ignoring {} with reason {}", event.eventType, reason)
    }
  }

  private companion object {
    private const val PRISONER_RECEIVED_EVENT_TYPE = "prison-offender-events.prisoner.received"
    private const val TRANSFERRED_REASON = "TRANSFERRED"

    /** Admissions that follow a period out of prison (a new/return admission) — R-01. */
    private val READMISSION_REASONS = setOf("NEW_ADMISSION", "READMISSION", "READMISSION_SWITCH_BOOKING")
    private val log = LoggerFactory.getLogger(PrisonerMovementListener::class.java)
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SQSMessage(
  @param:JsonProperty("Type") val type: String,
  @param:JsonProperty("Message") val message: String,
  @param:JsonProperty("MessageId") val messageId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerMovementEvent(
  val eventType: String,
  val additionalInformation: MovementAdditionalInformation? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovementAdditionalInformation(
  val nomsNumber: String? = null,
  val prisonId: String? = null,
  val reason: String? = null,
)
