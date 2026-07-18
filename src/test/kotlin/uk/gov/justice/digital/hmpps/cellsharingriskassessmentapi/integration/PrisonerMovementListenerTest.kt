package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PrisonerMovementListenerTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  @BeforeEach
  fun clean() {
    csraReviewRepository.deleteAll()
  }

  private fun inProgressReview(prisonerNumber: String, interimResult: CsraResult? = null): CsraReviewEntity = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = LocalDate.parse("2023-12-04"),
      type = CsraType.CSRA_INITIAL_REVIEW,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { LocalDate.parse("2023-12-04") },
      status = CsraReviewStatus.IN_PROGRESS,
      createdAt = LocalDateTime.parse("2023-12-04T09:00:00"),
      createdBy = "NQP56Y",
    ),
  )

  private fun receivedEvent(prisonerNumber: String, prisonId: String, reason: String) = """
    {
      "eventType":"prison-offender-events.prisoner.received",
      "additionalInformation":{"nomsNumber":"$prisonerNumber","prisonId":"$prisonId","reason":"$reason"},
      "version":"1.0",
      "occurredAt":"2023-12-05T12:00:00+00:00",
      "description":"A prisoner has been received into prison"
    }
  """.trimIndent()

  private fun send(prisonerNumber: String, prisonId: String, reason: String) {
    publishDomainEvent("prison-offender-events.prisoner.received", receivedEvent(prisonerNumber, prisonId, reason))
  }

  private fun awaitStatus(id: UUID, expected: CsraReviewStatus) {
    await untilCallTo { csraReviewRepository.findById(id).get().status } matches { it == expected }
  }

  @Test
  fun `transfer closes an in-progress review that has a provisional rating`() {
    val review = inProgressReview("A1111AA", interimResult = CsraResult.HIGH_GENERAL)

    send("A1111AA", "MDI", "TRANSFERRED")

    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)
    val closed = csraReviewRepository.findById(review.id!!).get()
    assertThat(closed.closureReason?.name).isEqualTo("NOT_COMPLETED_PRISONER_TRANSFER")
    assertThat(closed.closedAt).isNotNull()
    assertThat(closed.closedBy).isEqualTo("CELL_SHARING_RISK_ASSESSMENT_API")
    verify(telemetryClient).trackEvent(org.mockito.kotlin.eq("csra-in-progress-closed-on-admission"), org.mockito.kotlin.any(), org.mockito.kotlin.isNull())
  }

  @Test
  fun `transfer archives an in-progress review that has no rating`() {
    val review = inProgressReview("A2222AA")

    send("A2222AA", "MDI", "TRANSFERRED")

    awaitStatus(review.id!!, CsraReviewStatus.ARCHIVED)
  }

  @Test
  fun `readmission closes or archives an in-progress review`() {
    val review = inProgressReview("A3333AA", interimResult = CsraResult.HIGH_GENERAL)

    send("A3333AA", "MDI", "NEW_ADMISSION")

    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)
  }

  @Test
  fun `a return from court leaves an in-progress review untouched`() {
    val review = inProgressReview("A4444AA")

    send("A4444AA", "LEI", "RETURN_FROM_COURT")
    awaitCsraQueueDrained()

    assertThat(csraReviewRepository.findById(review.id!!).get().status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
  }

  @Test
  fun `redelivery of the same event does not error or change an already-closed review`() {
    val review = inProgressReview("A5555AA", interimResult = CsraResult.HIGH_GENERAL)

    send("A5555AA", "MDI", "TRANSFERRED")
    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)
    send("A5555AA", "MDI", "TRANSFERRED")
    awaitCsraQueueDrained()

    assertThat(csraReviewRepository.findById(review.id!!).get().status).isEqualTo(CsraReviewStatus.CLOSED)
  }
}
