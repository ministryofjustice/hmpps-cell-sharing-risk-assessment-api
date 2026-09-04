package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.InformationSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PrisonerMovementListenerTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

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

  /** A completed rating from a previous period of custody, reflected into the current-rating projection. */
  private fun completedRating(prisonerNumber: String, rating: CsraResult) {
    csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisonerNumber,
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2023-06-01"),
        type = CsraType.CSRA_INITIAL_REVIEW,
        finalResult = rating,
        finalResultDate = LocalDate.parse("2023-06-01"),
        status = CsraReviewStatus.COMPLETE,
        createdAt = LocalDateTime.parse("2023-06-01T09:00:00"),
        createdBy = "NQP56Y",
      ),
    )
    refreshCurrentRating(prisonerNumber)
  }

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
    assertThat(closed.closureReason).isEqualTo(CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER)
    assertThat(closed.closedAt).isNotNull()
    assertThat(closed.closedBy).isEqualTo("CELL_SHARING_RISK_ASSESSMENT_API")
    verify(telemetryClient).trackEvent(eq("csra-in-progress-closed-on-admission"), any(), isNull())
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

  /**
   * A readmission after release is not a transfer, and the closed record has to say which it was - the
   * movement type is recorded nowhere else on the row.
   */
  @ParameterizedTest
  @CsvSource("NEW_ADMISSION", "READMISSION", "READMISSION_SWITCH_BOOKING")
  fun `a review closed on readmission records the release closure reason`(reason: String) {
    val prisoner = "A31" + reason.take(2) + "AA"
    val review = inProgressReview(prisoner, interimResult = CsraResult.HIGH_GENERAL)

    send(prisoner, "MDI", reason)

    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)
    val closed = csraReviewRepository.findById(review.id!!).get()
    assertThat(closed.closureReason).isEqualTo(CsraClosureReason.NOT_COMPLETED_PRISONER_RELEASE)
    assertThat(closed.closedAt).isNotNull()
    assertThat(closed.closedBy).isEqualTo("CELL_SHARING_RISK_ASSESSMENT_API")
  }

  @Test
  fun `an unrated review archived on readmission records the release closure reason too`() {
    val review = inProgressReview("A3999AA")

    send("A3999AA", "MDI", "READMISSION")

    awaitStatus(review.id!!, CsraReviewStatus.ARCHIVED)
    assertThat(csraReviewRepository.findById(review.id!!).get().closureReason)
      .isEqualTo(CsraClosureReason.NOT_COMPLETED_PRISONER_RELEASE)
  }

  @Test
  fun `the telemetry reason matches the reason stored on the row`() {
    val review = inProgressReview("A3998AA", interimResult = CsraResult.HIGH_GENERAL)

    send("A3998AA", "MDI", "READMISSION")

    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)
    val properties = argumentCaptor<Map<String, String>>()
    verify(telemetryClient).trackEvent(eq("csra-in-progress-closed-on-admission"), properties.capture(), isNull())
    assertThat(properties.firstValue["reason"]).isEqualTo("NOT_COMPLETED_PRISONER_RELEASE")
    assertThat(properties.firstValue["movement"]).isEqualTo("readmission")
    assertThat(properties.firstValue["outcome"]).isEqualTo("CLOSED")
  }

  @Test
  fun `readmission resets the current rating to No rating even when a prior rated review exists`() {
    completedRating("A6666AA", CsraResult.STANDARD)
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A6666AA")!!.rating).isEqualTo(CsraResult.STANDARD)

    send("A6666AA", "MDI", "READMISSION")

    await.until { csraCurrentRatingRepository.findByPrisonerNumber("A6666AA")?.rating == null }

    // Clearing a rating changes the prisoner's CSRA, so it is announced like any other rating change. No
    // review produced it, so there is no id to carry and consumers re-read the current rating.
    val event = getDomainEvents(1).single()
    assertThat(event.eventType).isEqualTo("cell.sharing.risk.assessment.amended")
    assertThat(event.additionalInformation?.id).isNull()
    assertThat(event.additionalInformation?.nomsNumber).isEqualTo("A6666AA")
    assertThat(event.additionalInformation?.source).isEqualTo(InformationSource.DPS)
  }

  /**
   * Publication is asynchronous, so "nothing was published" is asserted by performing the silent action and
   * then a known-noisy one, and checking the noisy event is the only thing on the queue.
   */
  @Test
  fun `a readmission that clears nothing publishes no event`() {
    // Already at "No rating": the reset changes nothing, so there is nothing to announce.
    send("A7777AA", "MDI", "READMISSION")
    awaitCsraQueueDrained()

    completedRating("A7778AA", CsraResult.STANDARD)
    send("A7778AA", "MDI", "READMISSION")

    val event = getDomainEvents(1).single()
    assertThat(event.additionalInformation?.nomsNumber).isEqualTo("A7778AA")
  }

  @Test
  fun `a transfer publishes nothing, even when it closes a review carrying an interim rating`() {
    // R-02 retains the rating, so unlike a readmission the transfer path is deliberately silent.
    val review = inProgressReview("A8888AA", interimResult = CsraResult.HIGH_GENERAL)
    refreshCurrentRating("A8888AA")

    send("A8888AA", "MDI", "TRANSFERRED")
    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)

    completedRating("A8889AA", CsraResult.STANDARD)
    send("A8889AA", "MDI", "READMISSION")

    val event = getDomainEvents(1).single()
    assertThat(event.additionalInformation?.nomsNumber).isEqualTo("A8889AA")
  }

  /**
   * The reset used to be skin-deep: it cleared the projection but left the pre-release reviews eligible,
   * so the very next refresh - which every NOMIS migrate and sync performs - put the old rating back.
   */
  @Test
  fun `the No rating reset survives a later refresh`() {
    completedRating("A9001AA", CsraResult.STANDARD)

    send("A9001AA", "MDI", "READMISSION")
    await.until { csraCurrentRatingRepository.findByPrisonerNumber("A9001AA")?.rating == null }

    // What a NOMIS migrate or sync for this prisoner does next.
    refreshCurrentRating("A9001AA")

    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A9001AA")?.rating).isNull()
  }

  @Test
  fun `a rating saved after the readmission still becomes the current rating`() {
    completedRating("A9002AA", CsraResult.STANDARD)

    send("A9002AA", "MDI", "READMISSION")
    await.until { csraCurrentRatingRepository.findByPrisonerNumber("A9002AA")?.rating == null }

    // A new assessment in the new custody period is not superseded, so it sets the rating as normal.
    csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = "A9002AA",
        prisonId = "MDI",
        assessmentDate = LocalDate.parse("2023-12-06"),
        type = CsraType.CSRA_INITIAL_REVIEW,
        finalResult = CsraResult.HIGH_GENERAL,
        finalResultDate = LocalDate.parse("2023-12-06"),
        status = CsraReviewStatus.COMPLETE,
        createdAt = LocalDateTime.parse("2023-12-06T09:00:00"),
        createdBy = "NQP56Y",
      ),
    )
    refreshCurrentRating("A9002AA")

    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A9002AA")?.rating).isEqualTo(CsraResult.HIGH_GENERAL)
  }

  /**
   * R-02: a transfer retains the rating, so it must supersede nothing. Superseding from the shared
   * close/archive helper would silently wipe the rating of every transferred prisoner in the estate.
   */
  @Test
  fun `a transfer supersedes nothing and leaves the rating standing`() {
    completedRating("A9003AA", CsraResult.STANDARD)
    val review = inProgressReview("A9003AA", interimResult = CsraResult.HIGH_GENERAL)

    send("A9003AA", "MDI", "TRANSFERRED")
    awaitStatus(review.id!!, CsraReviewStatus.CLOSED)

    assertThat(csraReviewRepository.findAllByPrisonerNumberAndSupersededAtIsNull("A9003AA")).hasSize(2)
    refreshCurrentRating("A9003AA")
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A9003AA")?.rating).isEqualTo(CsraResult.HIGH_GENERAL)
  }

  @Test
  fun `a redelivered readmission does not move the supersede timestamp`() {
    completedRating("A9004AA", CsraResult.STANDARD)

    send("A9004AA", "MDI", "READMISSION")
    await.until { csraCurrentRatingRepository.findByPrisonerNumber("A9004AA")?.rating == null }
    val stampedAt = csraReviewRepository.findAll().single { it.prisonerNumber == "A9004AA" }.supersededAt
    assertThat(stampedAt).isNotNull()

    send("A9004AA", "MDI", "READMISSION")
    awaitCsraQueueDrained()

    assertThat(csraReviewRepository.findAll().single { it.prisonerNumber == "A9004AA" }.supersededAt).isEqualTo(stampedAt)
  }

  /** An unrated review is stamped too - it can gain a rating later when NOMIS next syncs it. */
  @Test
  fun `readmission supersedes unrated reviews as well as rated ones`() {
    val unrated = inProgressReview("A9005AA")

    send("A9005AA", "MDI", "READMISSION")
    awaitStatus(unrated.id!!, CsraReviewStatus.ARCHIVED)

    assertThat(csraReviewRepository.findById(unrated.id!!).get().supersededAt).isNotNull()
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

  @Test
  fun `a merge event on the same queue does not disturb in-progress movement work`() {
    // Both event types share one listener, so this guards the dispatch: a merge naming a prisoner with an
    // in-progress review must not take the received branch and close or archive it.
    val review = inProgressReview("A6666AA", interimResult = CsraResult.HIGH_GENERAL)

    publishDomainEvent(
      "prison-offender-events.prisoner.merged",
      """
        {
          "eventType":"prison-offender-events.prisoner.merged",
          "additionalInformation":{"nomsNumber":"A6666AA","removedNomsNumber":"A7777AA","reason":"MERGE"},
          "version":"1.0",
          "occurredAt":"2023-12-05T12:00:00+00:00",
          "description":"A prisoner has been merged from A7777AA to A6666AA"
        }
      """.trimIndent(),
    )
    awaitCsraQueueDrained()

    assertThat(csraReviewRepository.findById(review.id!!).get().status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
  }
}
