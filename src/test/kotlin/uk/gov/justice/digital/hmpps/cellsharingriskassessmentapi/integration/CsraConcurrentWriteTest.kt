package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.transaction.PlatformTransactionManager
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraAssessmentService
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMovementService
import uk.gov.justice.hmpps.test.kotlin.auth.WithMockAuthUser
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two writes to the same CSRA review landing together (MAPA-437).
 *
 * Every writer saves the whole `csra_review` row, so before the review was versioned the second of two
 * concurrent saves silently wrote back its stale copy over the first. Each test here stages that
 * interleaving with [ReviewRowRace] - the first write is held uncommitted until the second has read the
 * review and is blocked trying to save it - and shows the second write now fails rather than wins: a 409
 * `ConcurrentUpdate` over HTTP, or a retry against the current row for an SQS listener.
 *
 * The ticket's examples: a transfer wiping a provisional rating saved meanwhile, and a merge or booking move
 * returning a cancelled assessment to in progress.
 */
class CsraConcurrentWriteTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var csraAssessmentService: CsraAssessmentService

  @Autowired
  private lateinit var csraMovementService: CsraMovementService

  @Autowired
  private lateinit var transactionManager: PlatformTransactionManager

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var entityManager: EntityManager

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  private val race by lazy { ReviewRowRace(transactionManager, jdbcTemplate, entityManager) }

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")
  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    csraNextReviewRepository.deleteAll()
    csraCurrentRatingRepository.deleteAll()
    csraReviewRepository.deleteAll()
    switchOn("LEI", "BXI")
  }

  private fun startAssessment(prisonerNumber: String): UUID = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "LEI" }""")
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraAssessmentStarted>()
    .returnResult().responseBody!!.assessmentId

  private fun startReview(prisonerNumber: String): UUID = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/review")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "LEI" }""")
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraReviewStarted>()
    .returnResult().responseBody!!.reviewId

  private fun provisional(rating: CsraResult = CsraResult.STANDARD) = CsraAssessmentStageRequest(
    rating = rating,
    prisonId = "LEI",
    assessmentComment = "Rated while something else changed the assessment",
  )

  private fun submitProvisional(prisonerNumber: String, assessmentId: UUID) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment/$assessmentId/provisional")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "rating": "STANDARD", "prisonId": "LEI", "assessmentComment": "The competing rating" }""")
    .exchange()

  private fun submitInterim(prisonerNumber: String, reviewId: UUID) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisonerNumber/review/$reviewId/interim")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(
      """
      {
        "rating": "STANDARD",
        "prisonId": "LEI",
        "reviewComment": "Reviewed.",
        "reviewReason": "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING",
        "mdtChairName": "Sue Carter",
        "evidenceSources": [{"source":"OASYS"}],
        "riskTo": [],
        "vulnerabilities": []
      }
      """.trimIndent(),
    )
    .exchange()

  private fun move(from: String, to: String, reviewId: UUID) = webTestClient.put()
    .uri("/nomis-sync/move/from/$from/to/$to")
    .headers(setAuthorisation(roles = syncRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""["$reviewId"]""")
    .exchange()

  private fun sync(prisonerNumber: String, csraReviewId: UUID?, level: String) = webTestClient.post()
    .uri("/nomis-sync/sync/$prisonerNumber")
    .headers(setAuthorisation(roles = syncRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(
      """
      {
        ${csraReviewId?.let { """"csraReviewId": "$it",""" } ?: ""}
        "review": {
          "bookingId": 1234567,
          "nomisSequence": 1,
          "assessmentPrisonId": "LEI",
          "assessmentDate": "2023-06-01",
          "assessmentType": "CSR",
          "calculatedLevel": "$level",
          "score": 1000,
          "status": "A",
          "committeeCode": "REVIEW",
          "createdDateTime": "2023-06-01T12:34:56",
          "createdBy": "NQP56Y",
          "reviewLevel": "$level",
          "approvedLevel": "$level",
          "evaluationDate": "2023-06-02"
        }
      }
      """.trimIndent(),
    )
    .exchange()

  private fun receivedEvent(prisonerNumber: String, reason: String) = """
    {
      "eventType":"prison-offender-events.prisoner.received",
      "additionalInformation":{"nomsNumber":"$prisonerNumber","prisonId":"BXI","reason":"$reason"},
      "version":"1.0",
      "occurredAt":"2023-12-05T12:00:00+00:00",
      "description":"A prisoner has been received into prison"
    }
  """.trimIndent()

  private fun mergedEvent(retained: String, removed: String) = """
    {
      "eventType":"prison-offender-events.prisoner.merged",
      "additionalInformation":{"nomsNumber":"$retained","removedNomsNumber":"$removed","reason":"MERGE","bookingId":"1216772"},
      "version":"1.0",
      "occurredAt":"2023-12-05T12:00:00+00:00",
      "description":"A prisoner has been merged from $removed to $retained"
    }
  """.trimIndent()

  /**
   * Starts an HTTP call on another thread, so it can block on the review row the test transaction holds.
   * Returns a function that waits for the call and rethrows anything its assertions threw.
   */
  private fun inBackground(call: () -> Unit): () -> Unit {
    val executor = Executors.newSingleThreadExecutor()
    val pending = executor.submit(Callable { call() })
    executor.shutdown()
    return { pending.get(10, TimeUnit.SECONDS) }
  }

  private fun WebTestClient.ResponseSpec.expectConcurrentUpdate() = expectStatus().isEqualTo(409)
    .expectBody()
    .jsonPath("$.errorCode").isEqualTo("ConcurrentUpdate")
    .jsonPath("$.userMessage").isEqualTo("Conflict: The CSRA record was changed by another request at the same time. Reload it and try again")

  private fun review(id: UUID) = csraReviewRepository.findById(id).orElseThrow()

  @Test
  @WithMockAuthUser(username = "ASSESSOR", roles = ["CSRA_REVIEW__RW"])
  fun `a provisional rating saved while a transfer is handled is kept, and the retried message closes the assessment`() {
    val prisoner = "G0001GG"
    val assessmentId = startAssessment(prisoner)

    // The listener reads the draft while it is still unrated, so it decides to archive it; the rating then
    // commits underneath it. Before MAPA-437 the listener's stale copy won: archived, rating gone.
    race.commitOnceBlocked(
      first = { csraAssessmentService.submitProvisional(prisoner, assessmentId, provisional()) },
    ) { publishDomainEvent("prison-offender-events.prisoner.received", receivedEvent(prisoner, "TRANSFERRED")) }

    // The listener's first attempt fails on the version; the redelivered message re-reads the rated draft.
    await untilCallTo { review(assessmentId).status } matches { it == CsraReviewStatus.CLOSED }
    awaitCsraQueueDrained()
    val closed = review(assessmentId)
    assertThat(closed.interimResult).isEqualTo(CsraResult.STANDARD)
    assertThat(closed.closureReason).isEqualTo(CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER)
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)!!.rating).isEqualTo(CsraResult.STANDARD)
    assertThat(getDomainEvents(1).single().eventType).isEqualTo("cell.sharing.risk.assessment.created")
    // Reported once, for what actually happened - not for the archive that was rolled back.
    verify(telemetryClient).trackEvent(
      eq("csra-in-progress-closed-on-admission"),
      argThat { this["reviewId"] == assessmentId.toString() && this["outcome"] == "CLOSED" },
      isNull(),
    )
    verify(telemetryClient, never()).trackEvent(
      eq("csra-in-progress-closed-on-admission"),
      argThat { this["outcome"] == "ARCHIVED" },
      isNull(),
    )
  }

  @Test
  fun `a provisional rating cannot be saved over a draft a transfer archived meanwhile`() {
    val prisoner = "G0002GG"
    val assessmentId = startAssessment(prisoner)

    lateinit var awaitResponse: () -> Unit
    race.commitOnceBlocked(first = { csraMovementService.handleTransfer(prisoner, "BXI") }) {
      awaitResponse = inBackground { submitProvisional(prisoner, assessmentId).expectConcurrentUpdate() }
    }
    awaitResponse()

    val archived = review(assessmentId)
    assertThat(archived.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(archived.interimResult).isNull()
    // Nothing from the losing request survives: not the stage, not the rating, not an event.
    assertThat(csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)).isNull()
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)?.rating).isNull()
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  @WithMockAuthUser(username = "ASSESSOR", roles = ["CSRA_REVIEW__RW"])
  fun `a provisional rating submitted twice at once is saved once and the second gets a 409 rather than a 500`() {
    val prisoner = "G0003GG"
    val assessmentId = startAssessment(prisoner)

    // A double click. Both requests find no provisional stage; the review row is written before the stage
    // so the second fails on the review's version rather than on the stage table's unique index.
    lateinit var awaitResponse: () -> Unit
    race.commitOnceBlocked(first = { csraAssessmentService.submitProvisional(prisoner, assessmentId, provisional()) }) {
      awaitResponse = inBackground { submitProvisional(prisoner, assessmentId).expectConcurrentUpdate() }
    }
    awaitResponse()

    assertThat(review(assessmentId).interimResult).isEqualTo(CsraResult.STANDARD)
    assertThat(csraAssessmentStageRepository.findAllByCsraReviewId(assessmentId)).hasSize(1)
    assertThat(getDomainEvents(1).single().eventType).isEqualTo("cell.sharing.risk.assessment.created")
  }

  @Test
  fun `an interim review rating cannot be saved over a review a transfer archived meanwhile`() {
    val prisoner = "G0004GG"
    val reviewId = startReview(prisoner)

    lateinit var awaitResponse: () -> Unit
    race.commitOnceBlocked(first = { csraMovementService.handleTransfer(prisoner, "BXI") }) {
      awaitResponse = inBackground { submitInterim(prisoner, reviewId).expectConcurrentUpdate() }
    }
    awaitResponse()

    val archived = review(reviewId)
    assertThat(archived.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(archived.interimResult).isNull()
    assertThat(csraAssessmentStageRepository.findAllByCsraReviewId(reviewId)).isEmpty()
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  @WithMockAuthUser(username = "ASSESSOR", roles = ["CSRA_REVIEW__RW"])
  fun `a merge that read the retired number's draft cannot return a cancelled assessment to in progress`() {
    val removed = "G0005GG"
    val retained = "G0006GG"
    val assessmentId = startAssessment(removed)

    race.commitOnceBlocked(first = { csraAssessmentService.cancel(removed, assessmentId, "LEI") }) {
      publishDomainEvent("prison-offender-events.prisoner.merged", mergedEvent(retained, removed))
    }

    // The merge's first attempt fails on the version; the redelivered message moves the cancelled assessment.
    await untilCallTo { review(assessmentId).prisonerNumber } matches { it == retained }
    awaitCsraQueueDrained()
    val moved = review(assessmentId)
    assertThat(moved.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(moved.closureReason).isEqualTo(CsraClosureReason.CANCELLED)
    assertThat(moved.closedBy).isEqualTo("ASSESSOR")
    verify(telemetryClient).trackEvent(eq("csra-merge"), any(), isNull())
  }

  @Test
  @WithMockAuthUser(username = "ASSESSOR", roles = ["CSRA_REVIEW__RW"])
  fun `a booking move that read the draft cannot return a cancelled assessment to in progress, and succeeds when retried`() {
    val from = "G0007GG"
    val to = "G0008GG"
    val assessmentId = startAssessment(from)

    lateinit var awaitResponse: () -> Unit
    race.commitOnceBlocked(first = { csraAssessmentService.cancel(from, assessmentId, "LEI") }) {
      awaitResponse = inBackground { move(from, to, assessmentId).expectConcurrentUpdate() }
    }
    awaitResponse()
    assertThat(review(assessmentId).prisonerNumber).isEqualTo(from)

    // What the NOMIS to DPS sync service does with a 409: retry the message.
    move(from, to, assessmentId).expectStatus().isOk

    val moved = review(assessmentId)
    assertThat(moved.prisonerNumber).isEqualTo(to)
    assertThat(moved.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(moved.closureReason).isEqualTo(CsraClosureReason.CANCELLED)
  }

  @Test
  // The readmission is called directly here rather than through the listener, and its audit reads the
  // acting user, so the test thread needs one.
  @WithMockAuthUser(username = "ASSESSOR", roles = ["CSRA_REVIEW__RW"])
  fun `a NOMIS sync that read a review cannot undo a readmission superseding it, and succeeds when retried`() {
    val prisoner = "G0009GG"
    val reviewId = sync(prisoner, csraReviewId = null, level = "HI")
      .expectStatus().isCreated
      .expectBody<SyncResult>().returnResult().responseBody!!.csraReviewId
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)!!.rating).isEqualTo(CsraResult.HIGH)

    // A readmission after release supersedes the review (R-01). Before MAPA-437 a sync holding the
    // pre-readmission copy wrote superseded_at back to null, and the pre-release rating came straight back.
    lateinit var awaitResponse: () -> Unit
    race.commitOnceBlocked(first = { csraMovementService.handleReadmission(prisoner, "BXI") }) {
      awaitResponse = inBackground { sync(prisoner, reviewId, level = "STANDARD").expectConcurrentUpdate() }
    }
    awaitResponse()
    assertThat(review(reviewId).finalResult).isEqualTo(CsraResult.HIGH)

    sync(prisoner, reviewId, level = "STANDARD").expectStatus().isOk

    val synced = review(reviewId)
    assertThat(synced.finalResult).isEqualTo(CsraResult.STANDARD)
    assertThat(synced.supersededAt).isNotNull()
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)?.rating).isNull()
    // Created on the first sync, cleared by the readmission, amended by the retried sync.
    getDomainEvents(3)
  }
}
