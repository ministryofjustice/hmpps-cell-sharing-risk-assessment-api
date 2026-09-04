package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.InformationSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The prisoner-merge half of the `csra` queue. RETAINED is the surviving prisoner number, REMOVED the one
 * NOMIS deletes.
 */
class PrisonerMergeListenerTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  @BeforeEach
  fun clean() {
    csraNextReviewRepository.deleteAll()
    csraCurrentRatingRepository.deleteAll()
    csraReviewRepository.deleteAll()
  }

  // ---------------------------------------------------------------- fixtures

  private fun ratedReview(
    prisonerNumber: String,
    rating: CsraResult,
    assessmentDate: LocalDate,
    supersededAt: LocalDateTime? = null,
  ): CsraReviewEntity = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = assessmentDate,
      type = CsraType.CSRA_INITIAL_REVIEW,
      finalResult = rating,
      finalResultDate = assessmentDate,
      status = CsraReviewStatus.COMPLETE,
      createdAt = assessmentDate.atTime(9, 0),
      createdBy = "NQP56Y",
      supersededAt = supersededAt,
    ),
  )

  private fun mergedEvent(retained: String, removed: String) = """
    {
      "eventType":"prison-offender-events.prisoner.merged",
      "additionalInformation":{"nomsNumber":"$retained","removedNomsNumber":"$removed","reason":"MERGE","bookingId":"1216772"},
      "version":"1.0",
      "occurredAt":"2023-12-05T12:00:00+00:00",
      "description":"A prisoner has been merged from $removed to $retained"
    }
  """.trimIndent()

  private fun sendMerge(retained: String, removed: String) {
    publishDomainEvent("prison-offender-events.prisoner.merged", mergedEvent(retained, removed))
  }

  private fun awaitReviewsUnder(prisonerNumber: String, expected: Int) {
    await untilCallTo { csraReviewRepository.findAllByPrisonerNumber(prisonerNumber).size } matches { it == expected }
  }

  private fun currentRating(prisonerNumber: String) = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)

  // ---------------------------------------------------------------- tests

  @Test
  fun `every review moves to the retained prisoner number`() {
    ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-10"))
    ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-02-10"))
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2022-05-01"))
    refreshCurrentRating("A1111AA")
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    awaitReviewsUnder("A1111AA", 3)
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A2222BB")).isEmpty()
    assertThat(currentRating("A2222BB")).isNull()
  }

  @Test
  fun `the newest rated review wins whichever number it came in under, and is announced`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val newer = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(newer.id)

    val event = getDomainEvents().single()
    assertThat(event.eventType).isEqualTo("cell.sharing.risk.assessment.amended")
    assertThat(event.additionalInformation!!.nomsNumber).isEqualTo("A1111AA")
    assertThat(event.additionalInformation.removedNomsNumber).isEqualTo("A2222BB")
    assertThat(event.additionalInformation.id).isNull()
    assertThat(event.additionalInformation.source).isEqualTo(InformationSource.NOMIS)
    verify(telemetryClient).trackEvent(eq("csra-merge"), any(), isNull())
  }

  @Test
  fun `a merge that leaves the rating unchanged moves the history but publishes nothing`() {
    val standing = ratedReview("A1111AA", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A1111AA")
    ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    awaitReviewsUnder("A1111AA", 2)
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(standing.id)
    awaitCsraQueueDrained()
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
    verify(telemetryClient).trackEvent(eq("csra-merge"), any(), isNull())
  }

  @Test
  fun `a retained prisoner with no CSRA at all inherits the removed prisoner's rating`() {
    val only = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(only.id)
    assertThat(currentRating("A2222BB")).isNull()
  }

  @Test
  fun `a merge does not resurrect a rating the retained prisoner's readmission cleared`() {
    // The retained prisoner was released and readmitted: their history is superseded and they read as
    // "No rating" (R-01). The removed number's review predates that release, so it must not reinstate one.
    ratedReview("A1111AA", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-03-01"), supersededAt = LocalDateTime.parse("2023-05-01T09:00:00"))
    refreshCurrentRating("A1111AA")
    val old = ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-02-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    awaitReviewsUnder("A1111AA", 2)
    assertThat(csraReviewRepository.findById(old.id!!).get().supersededAt).isNotNull()
    assertThat(currentRating("A1111AA")!!.rating).isNull()
  }

  @Test
  fun `a review from the current custody period survives the merge unsuperseded`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-03-01"), supersededAt = LocalDateTime.parse("2023-05-01T09:00:00"))
    refreshCurrentRating("A1111AA")
    val current = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-08-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(csraReviewRepository.findById(current.id!!).get().supersededAt).isNull()
  }

  @Test
  fun `a merge for a prisoner number we hold nothing for is a no-op`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val setAtBefore = currentRating("A1111AA")!!.setAt

    sendMerge(retained = "A1111AA", removed = "A9999ZZ")

    awaitCsraQueueDrained()
    verify(telemetryClient).trackEvent(eq("csra-merge-no-op"), any(), isNull())
    verify(telemetryClient, never()).trackEvent(eq("csra-merge"), any(), isNull())
    assertThat(currentRating("A1111AA")!!.setAt).isEqualTo(setAtBefore)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `redelivering the same merge changes nothing and publishes nothing`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    sendMerge(retained = "A1111AA", removed = "A2222BB")
    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    getDomainEvents()
    val setAtAfterFirst = currentRating("A1111AA")!!.setAt

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    awaitCsraQueueDrained()
    assertThat(currentRating("A1111AA")!!.setAt).isEqualTo(setAtAfterFirst)
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A1111AA")).hasSize(2)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `exactly one next review row survives, the one set by the later review`() {
    val older = ratedReview("A1111AA", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val newer = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")
    saveNextReview("A1111AA", older.id!!, LocalDate.parse("2024-01-01"))
    saveNextReview("A2222BB", newer.id!!, LocalDate.parse("2024-06-01"))

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    await untilCallTo { csraNextReviewRepository.findAll().size } matches { it == 1 }
    val surviving = csraNextReviewRepository.findByPrisonerNumber("A1111AA")!!
    assertThat(surviving.setByReviewId).isEqualTo(newer.id)
    assertThat(surviving.nextReviewDate).isEqualTo(LocalDate.parse("2024-06-01"))
  }

  @Test
  fun `the removed prisoner's next review row is repointed when the retained prisoner has none`() {
    val review = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")
    saveNextReview("A2222BB", review.id!!, LocalDate.parse("2024-06-01"))

    sendMerge(retained = "A1111AA", removed = "A2222BB")

    await untilCallTo { csraNextReviewRepository.findByPrisonerNumber("A1111AA")?.nextReviewDate } matches {
      it == LocalDate.parse("2024-06-01")
    }
    assertThat(csraNextReviewRepository.findByPrisonerNumber("A2222BB")).isNull()
  }

  @Test
  fun `a merge of a prisoner number into itself is ignored`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")

    sendMerge(retained = "A1111AA", removed = "A1111AA")

    awaitCsraQueueDrained()
    verify(telemetryClient, never()).trackEvent(eq("csra-merge"), any(), isNull())
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A1111AA")).hasSize(1)
  }

  private fun saveNextReview(prisonerNumber: String, setByReviewId: UUID, date: LocalDate) {
    csraNextReviewRepository.saveAndFlush(
      CsraNextReviewEntity(
        prisonerNumber = prisonerNumber,
        nextReviewDate = date,
        setByReviewId = setByReviewId,
        updatedAt = LocalDateTime.parse("2023-06-01T09:00:00"),
        updatedBy = "NQP56Y",
      ),
    )
  }
}
