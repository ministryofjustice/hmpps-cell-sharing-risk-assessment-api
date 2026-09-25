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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.reactive.function.BodyInserters
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
 * The `PUT /nomis-sync/move/from/{fromPrisonerNumber}/to/{toPrisonerNumber}` endpoint, backed by
 * [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMoveService]. Unlike a prisoner
 * merge (see [PrisonerMergeListenerTest]) this repoints a *named, explicit* set of review ids that NOMIS
 * recorded against the wrong prisoner number (e.g. a booking.moved event) - it does not move everything a
 * prisoner number holds, and it is invoked synchronously over HTTP rather than via the `csra` SQS queue.
 */
class CsraMoveResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

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
      type = CsraType.CSRA_INITIAL_ASSESSMENT,
      finalResult = rating,
      finalResultDate = assessmentDate,
      status = CsraReviewStatus.COMPLETE,
      createdAt = assessmentDate.atTime(9, 0),
      createdBy = "NQP56Y",
      supersededAt = supersededAt,
    ),
  )

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

  private fun currentRating(prisonerNumber: String) = csraCurrentRatingRepository.findByPrisonerNumber(prisonerNumber)

  private fun expectCallMove(from: String, to: String, reviewIds: List<UUID>, roles: List<String>? = syncRole) = webTestClient
    .put()
    .uri("/nomis-sync/move/from/$from/to/$to")
    .let { if (roles != null) it.headers(setAuthorisation(roles = roles)) else it }
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(reviewIds.map { "\"$it\"" }.joinToString(prefix = "[", postfix = "]")))
    .exchange()
    .expectStatus()

  // ---------------------------------------------------------------- tests

  @Test
  fun `a move is handled for a prison CSRA is not switched on for - the move endpoint is deliberately not gated`() {
    // No active_agency row: IntegrationTestBase clears the table before every test. A move is NOMIS
    // correcting a review filed under the wrong prisoner number; it must happen whatever a prison's
    // rollout state, so this endpoint must never call CsraWriteSupport.rejectIfPrisonNotActive.
    val review = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(review.id!!)).isOk

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(csraReviewRepository.findByIdOrNull(review.id!!)?.prisonerNumber).isEqualTo("A1111AA")
  }

  @Test
  fun `only the named reviews move - other reviews for the source prisoner are left behind`() {
    val toMove = ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-10"))
    val leftBehind = ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-02-10"))
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2022-05-01"))
    refreshCurrentRating("A1111AA")
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(toMove.id!!)).isOk

    await untilCallTo { csraReviewRepository.findByIdOrNull(toMove.id!!)?.prisonerNumber } matches { it == "A1111AA" }
    assertThat(csraReviewRepository.findByIdOrNull(leftBehind.id!!)?.prisonerNumber).isEqualTo("A2222BB")
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A2222BB")).hasSize(1)
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A1111AA")).hasSize(2)
  }

  @Test
  fun `the newest rated review wins on the destination prisoner, and is announced`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val moved = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(moved.id)

    val events = getDomainEvents(2)
    assertThat(events).hasSize(2)
    assertThat(events).allSatisfy { event ->
      assertThat(event.eventType).isEqualTo("cell.sharing.risk.assessment.amended")
      assertThat(event.additionalInformation!!.id).isNull()
      assertThat(event.additionalInformation.source).isEqualTo(InformationSource.NOMIS)
      assertThat(event.additionalInformation.removedNomsNumber).isNull()
    }
    assertThat(events.map { it.additionalInformation!!.nomsNumber }).containsExactlyInAnyOrder("A1111AA", "A2222BB")
    verify(telemetryClient).trackEvent(eq("csra-reviews-moved"), any(), isNull())
  }

  @Test
  fun `a move that leaves both ratings unchanged moves the review but publishes nothing`() {
    val standing = ratedReview("A1111AA", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A1111AA")
    ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-05-01"))
    val moved = ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk

    await untilCallTo { csraReviewRepository.findByIdOrNull(moved.id!!)?.prisonerNumber } matches { it == "A1111AA" }
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(standing.id)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
    verify(telemetryClient).trackEvent(eq("csra-reviews-moved"), any(), isNull())
  }

  @Test
  fun `a destination prisoner with no CSRA at all inherits the moved review's rating`() {
    val moved = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(currentRating("A1111AA")!!.setByReviewId).isEqualTo(moved.id)
    // The source prisoner is left with no reviews at all, so their projection is recomputed to "No rating"
    // rather than deleted - unlike a merge, where the retired prisoner number ceases to exist entirely.
    assertThat(currentRating("A2222BB")!!.rating).isNull()
  }

  @Test
  fun `the source prisoner keeps whatever rating their remaining reviews still support`() {
    ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    val moved = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk

    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    assertThat(currentRating("A2222BB")!!.rating).isEqualTo(CsraResult.STANDARD)
  }

  @Test
  fun `a move leaves next review rows untouched, unlike a merge`() {
    // A move repoints only the named reviews. It is a correction of a booking mistake, not a resolution of
    // two prisoner numbers into one, so - unlike CsraMergeService - it does not repoint or delete the
    // next-review projection for either prisoner number.
    val older = ratedReview("A1111AA", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val newer = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")
    saveNextReview("A1111AA", older.id!!, LocalDate.parse("2024-01-01"))
    saveNextReview("A2222BB", newer.id!!, LocalDate.parse("2024-06-01"))

    expectCallMove("A2222BB", "A1111AA", listOf(newer.id!!)).isOk

    await untilCallTo { csraReviewRepository.findByIdOrNull(newer.id!!)?.prisonerNumber } matches { it == "A1111AA" }
    assertThat(csraNextReviewRepository.findByPrisonerNumber("A1111AA")!!.nextReviewDate).isEqualTo(LocalDate.parse("2024-01-01"))
    assertThat(csraNextReviewRepository.findByPrisonerNumber("A2222BB")!!.nextReviewDate).isEqualTo(LocalDate.parse("2024-06-01"))
  }

  @Test
  fun `a review id that does not exist is silently ignored`() {
    val review = ratedReview("A2222BB", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A2222BB")
    val setAtBefore = currentRating("A2222BB")!!.setAt

    expectCallMove("A2222BB", "A1111AA", listOf(UUID.randomUUID())).isOk

    awaitCsraQueueDrained()
    verify(telemetryClient).trackEvent(eq("csra-reviews-moved-no-op"), any(), isNull())
    verify(telemetryClient, never()).trackEvent(eq("csra-reviews-moved"), any(), isNull())
    assertThat(csraReviewRepository.findById(review.id!!).get().prisonerNumber).isEqualTo("A2222BB")
    assertThat(currentRating("A2222BB")!!.setAt).isEqualTo(setAtBefore)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `redelivering the same move changes nothing and publishes nothing`() {
    ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")
    val moved = ratedReview("A2222BB", CsraResult.HIGH_GENERAL, LocalDate.parse("2023-06-01"))
    refreshCurrentRating("A2222BB")

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk
    await untilCallTo { currentRating("A1111AA")?.rating } matches { it == CsraResult.HIGH_GENERAL }
    getDomainEvents(2)
    val setAtAfterFirst = currentRating("A1111AA")!!.setAt

    expectCallMove("A2222BB", "A1111AA", listOf(moved.id!!)).isOk

    awaitCsraQueueDrained()
    verify(telemetryClient).trackEvent(eq("csra-reviews-moved-no-op"), any(), isNull())
    assertThat(currentRating("A1111AA")!!.setAt).isEqualTo(setAtAfterFirst)
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A1111AA")).hasSize(2)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `moving a prisoner's reviews onto themselves is rejected`() {
    val review = ratedReview("A1111AA", CsraResult.STANDARD, LocalDate.parse("2023-01-01"))
    refreshCurrentRating("A1111AA")

    expectCallMove("A1111AA", "A1111AA", listOf(review.id!!)).isBadRequest

    verify(telemetryClient, never()).trackEvent(eq("csra-reviews-moved"), any(), isNull())
    assertThat(csraReviewRepository.findAllByPrisonerNumber("A1111AA")).hasSize(1)
  }

  @Test
  fun `returns 401 without a token`() {
    expectCallMove("A2222BB", "A1111AA", listOf(UUID.randomUUID()), roles = null).isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    expectCallMove("A2222BB", "A1111AA", listOf(UUID.randomUUID()), roles = listOf("ROLE_SOMETHING_ELSE")).isForbidden
  }
}
