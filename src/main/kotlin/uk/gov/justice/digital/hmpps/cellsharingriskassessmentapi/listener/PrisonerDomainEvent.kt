package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.listener

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SQSMessage(
  @param:JsonProperty("Type") val type: String,
  @param:JsonProperty("Message") val message: String,
  @param:JsonProperty("MessageId") val messageId: String? = null,
)

/**
 * An inbound HMPPS domain event from the `csra` queue. One shape covers every event type we consume —
 * they all carry the prisoner in `additionalInformation` and differ only in which fields are populated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerDomainEvent(
  val eventType: String,
  val additionalInformation: PrisonerEventAdditionalInformation? = null,
)

/**
 * The union of the `additionalInformation` fields we read across the event types on this queue.
 *
 * - `prison-offender-events.prisoner.received` populates [nomsNumber], [prisonId] and [reason].
 * - `prison-offender-events.prisoner.merged` populates [nomsNumber] (the *retained* number),
 *   [removedNomsNumber] (the retired one), [reason] `MERGE` and [bookingId].
 *
 * Every field is nullable because the producer decides what a given event type carries; the handlers
 * validate what they actually need rather than trusting the shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerEventAdditionalInformation(
  val nomsNumber: String? = null,
  val prisonId: String? = null,
  val reason: String? = null,
  val removedNomsNumber: String? = null,
  val bookingId: String? = null,
)
