package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.annotations.WithSpan
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.publish
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.also
import kotlin.jvm.java

@Service
class SnsService(hmppsQueueService: HmppsQueueService, private val objectMapper: ObjectMapper) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  private val domaineventsTopic by lazy {
    hmppsQueueService.findByTopicId("domainevents")
      ?: throw kotlin.RuntimeException("Topic with name domainevents doesn't exist")
  }

  @WithSpan(value = "hmpps-domain-events-topic", kind = SpanKind.PRODUCER)
  fun publishDomainEvent(
    eventType: CSRADomainEventType,
    description: String,
    occurredAt: LocalDateTime,
    additionalInformation: AdditionalInformation? = null,
  ) {
    publishToDomainEventsTopic(
      HMPPSDomainEvent(
        eventType.value,
        additionalInformation,
        occurredAt.atZone(ZoneId.systemDefault()).toInstant(),
        description,
      ),
    )
  }

  private fun publishToDomainEventsTopic(payload: HMPPSDomainEvent) {
    if (payload.eventType != null) {
      log.debug("Event {} for id {}", payload.eventType, payload.additionalInformation)
      domaineventsTopic.publish(
        payload.eventType,
        objectMapper.writeValueAsString(payload),
      )
        .also { log.info("Published event to outbound topic. Type: ${payload.eventType}") }
    }
  }
}

data class AdditionalInformation(
  val id: UUID? = null,
  val key: String? = null,
  val source: InformationSource? = null,
)

data class HMPPSDomainEvent(
  val eventType: String? = null,
  val additionalInformation: AdditionalInformation?,
  val version: String,
  val occurredAt: String,
  val description: String,
) {
  constructor(
    eventType: String,
    additionalInformation: AdditionalInformation?,
    occurredAt: Instant,
    description: String,
  ) : this(
    eventType,
    additionalInformation,
    "1.0",
    occurredAt.toOffsetDateFormat(),
    description,
  )
}

enum class CSRADomainEventType(val value: String, val description: String, val auditType: AuditType) {
  CSRA_CREATED(
    "cell.sharing.risk.assessment.created",
    "A cell sharing risk assessment has been created",
    AuditType.CSRA_CREATED,
  ),
  CSRA_AMENDED(
    "cell.sharing.risk.assessment.amended",
    "A cell sharing risk assessment has been amended",
    AuditType.CSRA_AMENDED,
  ),
}

fun Instant.toOffsetDateFormat(): String = atZone(ZoneId.of("Europe/London")).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
