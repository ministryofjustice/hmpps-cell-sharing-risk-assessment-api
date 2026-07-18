package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.config.LocalStackContainer
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.config.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.HMPPSDomainEvent
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsTopic
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.publish
import java.time.Clock

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SqsIntegrationTestBase : IntegrationTestBase() {

  @MockitoBean
  private lateinit var clock: Clock

  @BeforeEach
  fun setupClock() {
    whenever(clock.instant()).thenReturn(TestBase.clock.instant())
    whenever(clock.zone).thenReturn(TestBase.clock.zone)
  }

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  private val auditQueue by lazy { hmppsQueueService.findByQueueId("audit") as HmppsQueue }
  private val testDomainEventQueue by lazy { hmppsQueueService.findByQueueId("test") as HmppsQueue }
  private val csraQueue by lazy { hmppsQueueService.findByQueueId("csra") as HmppsQueue }
  private val domainEventsTopic by lazy { hmppsQueueService.findByTopicId("domainevents") as HmppsTopic }

  @BeforeEach
  fun cleanQueue() {
    auditQueue.sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(auditQueue.queueUrl).build())
    testDomainEventQueue.sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(testDomainEventQueue.queueUrl).build())
    csraQueue.sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(csraQueue.queueUrl).build())
    auditQueue.sqsClient.countMessagesOnQueue(auditQueue.queueUrl).get()
    testDomainEventQueue.sqsClient.countMessagesOnQueue(testDomainEventQueue.queueUrl).get()
    csraQueue.sqsClient.countMessagesOnQueue(csraQueue.queueUrl).get()
  }

  /** Publishes an HMPPS domain event to the domainevents topic (routed to the csra queue by its filter). */
  fun publishDomainEvent(eventType: String, payload: String) {
    domainEventsTopic.publish(eventType, payload)
  }

  /** Waits until the csra inbound queue has been fully drained (the listener has processed everything). */
  fun awaitCsraQueueDrained() {
    await untilCallTo { csraQueue.sqsClient.countMessagesOnQueue(csraQueue.queueUrl).get() } matches { it == 0 }
  }

  companion object {
    private val localStackContainer = LocalStackContainer.instance

    @Suppress("unused")
    @JvmStatic
    @DynamicPropertySource
    fun testcontainers(registry: DynamicPropertyRegistry) {
      localStackContainer?.also { setLocalStackProperties(it, registry) }
    }
  }

  fun getNumberOfMessagesCurrentlyOnQueue(): Int = testDomainEventQueue.sqsClient.countMessagesOnQueue(testDomainEventQueue.queueUrl).get()

  fun getDomainEvents(messageCount: Int = 1): List<HMPPSDomainEvent> {
    val sqsClient = testDomainEventQueue.sqsClient

    val messages: MutableList<HMPPSDomainEvent> = mutableListOf()
    await untilCallTo {
      messages.addAll(
        sqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(testDomainEventQueue.queueUrl).build())
          .get()
          .messages()
          .map { objectMapper.readValue(it.body(), HMPPSMessage::class.java) }
          .map { objectMapper.readValue(it.Message, HMPPSDomainEvent::class.java) },
      )
    } matches { messages.size == messageCount }

    return messages
  }
}

data class HMPPSEventType(val Value: String, val Type: String)
data class HMPPSMessageAttributes(val eventType: HMPPSEventType)
data class HMPPSMessage(
  val Message: String,
  val MessageAttributes: HMPPSMessageAttributes,
)
