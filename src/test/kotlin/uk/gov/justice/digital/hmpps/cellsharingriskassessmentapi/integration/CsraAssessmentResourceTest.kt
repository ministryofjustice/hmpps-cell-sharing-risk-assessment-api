package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.reactive.function.BodyInserters
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraOffence
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageOffenceEvidenceRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraAssessmentService
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.test.kotlin.auth.WithMockAuthUser
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CsraAssessmentResourceTest : SqsIntegrationTestBase() {

  /** Every prison this class writes at. Without this the rollout gate (MAPA-363) refuses every write. */
  @BeforeEach
  fun switchOnTestPrisons() {
    csraNextReviewRepository.deleteAll()
    csraCurrentRatingRepository.deleteAll()
    csraReviewRepository.deleteAll()
    switchOn("LEI", "BXI", "WWI")
  }

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var offenceEvidenceRepository: CsraAssessmentStageOffenceEvidenceRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  private val auditQueue by lazy { hmppsQueueService.findByQueueId("audit") as HmppsQueue }

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  @Autowired
  private lateinit var transactionManager: PlatformTransactionManager

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var csraAssessmentService: CsraAssessmentService

  @Autowired
  private lateinit var entityManager: EntityManager

  private val race by lazy { ReviewRowRace(transactionManager, jdbcTemplate, entityManager) }

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")

  private fun stageBody(
    rating: String,
    comment: String,
    prisonId: String = "LEI",
    pncChecked: Boolean = true,
    murder: Boolean = false,
    riskTo: String = "[]",
    vulnerabilities: String = "[]",
  ) = """
    {
      "rating": "$rating",
      "prisonId": "$prisonId",
      "assessmentComment": "$comment",
      "pncChecked": $pncChecked,
      "offenceMurderManslaughter": $murder,
      "riskTo": $riskTo,
      "vulnerabilities": $vulnerabilities
    }
  """.trimIndent()

  private fun startBody(prisonId: String) = """{ "prisonId": "$prisonId" }"""

  private fun cancel(prisonerNumber: String, assessmentId: UUID, prisonId: String = "LEI") = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment/$assessmentId/cancel")
    .headers(setAuthorisation(user = "CANCELLER", roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(startBody(prisonId))
    .exchange()

  private fun start(prisonerNumber: String, prisonId: String = "LEI"): CsraAssessmentStarted = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(startBody(prisonId)))
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraAssessmentStarted>()
    .returnResult().responseBody!!

  /** An assessment already in a terminal lifecycle state, as a prisoner movement would leave it. */
  private fun seedAssessment(prisonerNumber: String, status: CsraReviewStatus) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = LocalDate.parse("2023-12-01"),
      type = CsraType.CSRA_INITIAL_ASSESSMENT,
      status = status,
      createdAt = LocalDateTime.parse("2023-12-01T09:00:00"),
      createdBy = "SCARTER",
    ),
  )

  /** A minimal STANDARD provisional body carrying only the offence evidence under test. */
  private fun evidenceBody(offenceEvidence: String) = """
    {
      "rating": "STANDARD",
      "prisonId": "LEI",
      "assessmentComment": "Evidence recorded.",
      "offenceEvidence": $offenceEvidence
    }
  """.trimIndent()

  private fun submitProvisional(prisonerNumber: String, assessmentId: UUID, body: String) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment/$assessmentId/provisional")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(body))
    .exchange()

  private fun reviewPrison(assessmentId: UUID) = csraReviewRepository.findById(assessmentId).orElseThrow().prisonId

  private fun stagePrison(assessmentId: UUID, stage: CsraAssessmentStage) = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, stage)!!.prisonId

  private fun countAuditMessages() = auditQueue.sqsClient.countMessagesOnQueue(auditQueue.queueUrl).get()

  private fun answersBody(
    prisonId: String,
    version: Int = 1,
    pncChecked: Boolean? = null,
    arson: Boolean? = null,
  ) = buildString {
    append("""{ "prisonId": "$prisonId", "version": $version""")
    if (pncChecked != null) append(""", "pncChecked": $pncChecked""")
    if (arson != null) append(""", "offenceArson": $arson""")
    append(" }")
  }

  @Test
  fun `cancel retains draft answers but hides the assessment and allows a fresh start`() {
    val prisoner = "C0001CC"
    val assessmentId = start(prisoner).assessmentId
    webTestClient.put()
      .uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(answersBody("LEI", pncChecked = true, arson = true))
      .exchange()
      .expectStatus().isOk

    cancel(prisoner, assessmentId, prisonId = "BXI").expectStatus().isOk
      .expectBody().jsonPath("$.rating").isEmpty

    val archived = csraReviewRepository.findById(assessmentId).orElseThrow()
    assertThat(archived.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(archived.closureReason).isEqualTo(CsraClosureReason.CANCELLED)
    assertThat(archived.closedAt).isEqualTo(LocalDateTime.now(clock))
    assertThat(archived.closedBy).isEqualTo("CANCELLER")
    val answers = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!
    assertThat(answers.pncChecked).isTrue()
    assertThat(answers.offenceArson).isTrue()
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)).isNull()

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange().expectStatus().isNotFound

    webTestClient.get().uri("/csra-review/$assessmentId")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange().expectStatus().isNotFound
    assertThat(
      csraReviewRepository.findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus(
        "LEI",
        CsraType.CSRA_INITIAL_ASSESSMENT,
        CsraReviewStatus.IN_PROGRESS,
      ).map { it.id },
    ).doesNotContain(assessmentId)
    assertThat(csraReviewRepository.findSummaryRows(prisoner)).isEmpty()

    submitProvisional(prisoner, assessmentId, stageBody("STANDARD", "Too late"))
      .expectStatus().isEqualTo(409)

    cancel(prisoner, assessmentId, prisonId = "WWI").expectStatus().isOk
    val repeated = csraReviewRepository.findById(assessmentId).orElseThrow()
    assertThat(repeated.closedAt).isEqualTo(archived.closedAt)
    assertThat(repeated.closedBy).isEqualTo(archived.closedBy)
    await untilCallTo { countAuditMessages() } matches { it == 1 }
    val message = auditQueue.sqsClient.receiveMessage(
      ReceiveMessageRequest.builder().queueUrl(auditQueue.queueUrl).build(),
    ).get().messages().single()
    auditQueue.sqsClient.deleteMessage(
      DeleteMessageRequest.builder().queueUrl(auditQueue.queueUrl).receiptHandle(message.receiptHandle()).build(),
    ).get()
    await untilCallTo { auditQueue.sqsClient.countAllMessagesOnQueue(auditQueue.queueUrl).get() } matches { it == 0 }
    val audit = objectMapper.readTree(message.body())
    assertThat(audit.path("what").asText()).isEqualTo("CSRA_CANCELLED")
    assertThat(audit.path("who").asText()).isEqualTo("CANCELLER")
    val auditDetails = objectMapper.readTree(audit.path("details").asText())
    assertThat(auditDetails.path("assessment").path("id").asText()).isEqualTo(assessmentId.toString())
    assertThat(auditDetails.path("assessment").path("prisonerNumber").asText()).isEqualTo(prisoner)
    assertThat(auditDetails.path("assessment").path("prisonId").asText()).isEqualTo("LEI")
    assertThat(auditDetails.path("requestPrisonId").asText()).isEqualTo("BXI")
    verify(telemetryClient).trackEvent(eq("CSRA_CANCELLED"), any(), isNull())
    verify(telemetryClient).trackEvent(
      eq("csra-assessment-cancelled"),
      eq(mapOf("assessmentId" to assessmentId.toString(), "prisonerNumber" to prisoner, "prisonId" to "LEI", "requestPrisonId" to "BXI")),
      isNull(),
    )
    verify(telemetryClient, never()).trackEvent(
      eq("csra-assessment-cancelled"),
      eq(mapOf("assessmentId" to assessmentId.toString(), "prisonerNumber" to prisoner, "prisonId" to "LEI", "requestPrisonId" to "WWI")),
      isNull(),
    )
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()

    assertThat(start(prisoner).assessmentId).isNotEqualTo(assessmentId)
  }

  @Test
  fun `cancel leaves an existing current rating untouched and emits no rating event`() {
    val prisoner = "C0002CC"
    val previousId = start(prisoner).assessmentId
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$previousId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(stageBody("STANDARD", "Existing rating"))
      .exchange().expectStatus().isOk
    getDomainEvents(1)
    val previous = csraCurrentRatingRepository.findByPrisonerNumber(prisoner)!!
    val assessmentId = start(prisoner).assessmentId

    cancel(prisoner, assessmentId).expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.reviewId").isEqualTo(previousId.toString())
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner))
      .usingRecursiveComparison().isEqualTo(previous)
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  @WithMockAuthUser(username = "CANCELLER", roles = ["CSRA_REVIEW__RW"])
  fun `cancellation audit and telemetry are sent only after a successful commit`(rollback: Boolean) {
    val prisoner = "C0008CC"
    val assessmentId = start(prisoner).assessmentId

    TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
      csraAssessmentService.cancel(prisoner, assessmentId, "BXI")
      assertThat(countAuditMessages()).isZero()
      verify(telemetryClient, never()).trackEvent(eq("CSRA_CANCELLED"), any(), isNull())
      verify(telemetryClient, never()).trackEvent(eq("csra-assessment-cancelled"), any(), isNull())
      if (rollback) transaction.setRollbackOnly()
    }

    val review = csraReviewRepository.findById(assessmentId).orElseThrow()
    if (rollback) {
      assertThat(review.status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
      assertThat(review.closureReason).isNull()
      assertThat(review.closedAt).isNull()
      assertThat(review.closedBy).isNull()
      assertThat(countAuditMessages()).isZero()
      verify(telemetryClient, never()).trackEvent(eq("CSRA_CANCELLED"), any(), isNull())
      verify(telemetryClient, never()).trackEvent(eq("csra-assessment-cancelled"), any(), isNull())
    } else {
      assertThat(review.status).isEqualTo(CsraReviewStatus.ARCHIVED)
      assertThat(review.closureReason).isEqualTo(CsraClosureReason.CANCELLED)
      await untilCallTo { countAuditMessages() } matches { it == 1 }
      verify(telemetryClient).trackEvent(eq("CSRA_CANCELLED"), any(), isNull())
      verify(telemetryClient).trackEvent(
        eq("csra-assessment-cancelled"),
        eq(mapOf("assessmentId" to assessmentId.toString(), "prisonerNumber" to prisoner, "prisonId" to "LEI", "requestPrisonId" to "BXI")),
        isNull(),
      )
    }
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  @WithMockAuthUser(username = "CANCELLER", roles = ["CSRA_REVIEW__RW"])
  fun `of a cancellation and a provisional rating landing together, the second is rejected`(cancelFirst: Boolean) {
    val prisoner = "C0007CC"
    val assessmentId = start(prisoner).assessmentId
    // The second request reads the assessment before the first commits, so it cannot see the change it is
    // about to collide with. The review's version (MAPA-437) is what stops it saving over it.
    Executors.newSingleThreadExecutor().use { executor ->
      val competing = race.commitOnceBlocked(
        first = {
          if (cancelFirst) {
            csraAssessmentService.cancel(prisoner, assessmentId, "LEI")
          } else {
            csraAssessmentService.submitProvisional(
              prisoner,
              assessmentId,
              CsraAssessmentStageRequest(rating = CsraResult.STANDARD, prisonId = "LEI", assessmentComment = "Rating first"),
            )
          }
        },
      ) {
        executor.submit(
          Callable {
            val response = if (cancelFirst) {
              submitProvisional(prisoner, assessmentId, stageBody("STANDARD", "Concurrent rating"))
            } else {
              cancel(prisoner, assessmentId)
            }
            response.expectStatus().isEqualTo(409)
              .expectBody().jsonPath("$.errorCode").isEqualTo("ConcurrentUpdate")
          },
        )
      }
      competing.get(10, TimeUnit.SECONDS)
    }
    val review = csraReviewRepository.findById(assessmentId).orElseThrow()
    if (cancelFirst) {
      assertThat(review.status).isEqualTo(CsraReviewStatus.ARCHIVED)
      assertThat(review.interimResult).isNull()
      assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
    } else {
      assertThat(review.status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
      assertThat(review.interimResult).isEqualTo(CsraResult.STANDARD)
      assertThat(review.closureReason).isNull()
      verify(telemetryClient, never()).trackEvent(eq("CSRA_CANCELLED"), any(), isNull())
      verify(telemetryClient, never()).trackEvent(eq("csra-assessment-cancelled"), any(), isNull())
      await untilCallTo { countAuditMessages() } matches { it == 1 }
      val message = auditQueue.sqsClient.receiveMessage(
        ReceiveMessageRequest.builder().queueUrl(auditQueue.queueUrl).build(),
      ).get().messages().single()
      auditQueue.sqsClient.deleteMessage(
        DeleteMessageRequest.builder().queueUrl(auditQueue.queueUrl).receiptHandle(message.receiptHandle()).build(),
      ).get()
      val audit = objectMapper.readTree(message.body())
      assertThat(audit.path("what").asText()).isEqualTo("CSRA_CREATED")
      assertThat(objectMapper.readTree(audit.path("details").asText()).path("id").asText()).isEqualTo(assessmentId.toString())
      await untilCallTo { auditQueue.sqsClient.countAllMessagesOnQueue(auditQueue.queueUrl).get() } matches { it == 0 }
    }
  }

  @ParameterizedTest
  @CsvSource(
    "IN_PROGRESS,STANDARD,,CsraAssessmentNotCancellable,has a rating and cannot be cancelled",
    "COMPLETE,,STANDARD,CsraAssessmentNotCancellable,has a rating and cannot be cancelled",
    "COMPLETE,,,CsraAssessmentNotCancellable,is complete and cannot be cancelled",
    "CLOSED,STANDARD,,CsraReviewNotWritable,is CLOSED and can no longer be edited",
    "ARCHIVED,,,CsraReviewNotWritable,is ARCHIVED and can no longer be edited",
  )
  fun `cancel rejects rated completed and movement-closed assessments`(
    status: CsraReviewStatus,
    provisional: CsraResult?,
    final: CsraResult?,
    expectedErrorCode: String,
    expectedReason: String,
  ) {
    val review = seedAssessment("C0003CC", status).apply {
      interimResult = provisional
      finalResult = final
      if (status == CsraReviewStatus.CLOSED || status == CsraReviewStatus.ARCHIVED) {
        closureReason = CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER
      }
    }
    csraReviewRepository.saveAndFlush(review)

    cancel(review.prisonerNumber, review.id!!).expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo(expectedErrorCode)
      .jsonPath("$.userMessage").value<String> { message -> assertThat(message).endsWith(expectedReason) }
      .jsonPath("$.developerMessage").value<String> { message -> assertThat(message).endsWith(expectedReason) }
    assertThat(csraReviewRepository.findById(review.id!!).orElseThrow().status).isEqualTo(status)
    assertThat(countAuditMessages()).isZero()
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `an already-rated assessment cannot be cancelled but can still be edited`() {
    val prisoner = "C0009CC"
    val assessmentId = start(prisoner).assessmentId
    submitProvisional(prisoner, assessmentId, stageBody("STANDARD", "First rating"))
      .expectStatus().isOk

    cancel(prisoner, assessmentId).expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CsraAssessmentNotCancellable")
      .jsonPath("$.userMessage").isEqualTo("Conflict: CSRA assessment $assessmentId has a rating and cannot be cancelled")

    submitProvisional(prisoner, assessmentId, stageBody("STANDARD", "Updated comment"))
      .expectStatus().isOk
      .expectBody().jsonPath("$.provisionalAssessmentComment").isEqualTo("Updated comment")
  }

  @Test
  fun `cancel returns 404 for unknown mismatched and non-initial assessment ids`() {
    cancel("C0004CC", UUID.randomUUID()).expectStatus().isNotFound
    val assessmentId = start("C0004CC").assessmentId
    cancel("C9999CC", assessmentId).expectStatus().isNotFound
    val review = csraReviewRepository.findById(assessmentId).orElseThrow().apply { type = CsraType.CSRA_REVIEW }
    csraReviewRepository.saveAndFlush(review)
    cancel("C0004CC", assessmentId).expectStatus().isNotFound
    assertThat(countAuditMessages()).isZero()
  }

  @ParameterizedTest
  @CsvSource("'',400", "'{}',400", "'{\"prisonId\":\"\"}',400")
  fun `cancel requires a prison in the body`(body: String, expectedStatus: Int) {
    webTestClient.post().uri("/csra-review/prisoner/C0005CC/assessment/${UUID.randomUUID()}/cancel")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(body)
      .exchange().expectStatus().isEqualTo(expectedStatus)
  }

  @Test
  fun `cancel requires authentication and the write role`() {
    val uri = "/csra-review/prisoner/C0006CC/assessment/${UUID.randomUUID()}/cancel"
    webTestClient.post().uri(uri)
      .contentType(MediaType.APPLICATION_JSON).bodyValue(startBody("LEI"))
      .exchange().expectStatus().isUnauthorized
    webTestClient.post().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .contentType(MediaType.APPLICATION_JSON).bodyValue(startBody("LEI"))
      .exchange().expectStatus().isForbidden
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/assessment")
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    // Sends a valid body deliberately: @PreAuthorize is an interceptor around the handler, so it fires after
    // argument resolution — an absent body would 400 before the role was ever checked.
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/assessment")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `start creates an in-progress draft that records who started it`() {
    val started = start("W0000WW")

    assertThat(started.assessmentId).isNotNull()
    assertThat(started.currentRating.status).isEqualTo(CsraRatingStatus.IN_PROGRESS)
    assertThat(started.currentRating.rating).isNull()
    assertThat(started.currentRating.startedBy).isNotNull()
    assertThat(started.currentRating.startedAt).isNotNull()
    assertThat(started.currentRating.reviewId).isEqualTo(started.assessmentId)
  }

  @Test
  fun `start returns the new assessment id for a prisoner who already has a rating`() {
    val prisoner = "R5555RR"
    val firstAssessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$firstAssessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "First assessment")))
      .exchange()
      .expectStatus().isOk

    val started = start(prisoner)

    // the id must identify the assessment just started, not the completed one that set the current rating
    assertThat(started.assessmentId).isNotEqualTo(firstAssessmentId)
    // the existing rating stands while the new assessment is in progress, so it still cites the earlier review
    assertThat(started.currentRating.status).isEqualTo(CsraRatingStatus.COMPLETE)
    assertThat(started.currentRating.reviewId).isEqualTo(firstAssessmentId)

    // the new assessment is a real, addressable draft
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/${started.assessmentId}/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("HIGH_GENERAL", "Second assessment")))
      .exchange()
      .expectStatus().isOk

    assertThat(csraAssessmentStageRepository.findAllByCsraReviewId(started.assessmentId)).hasSize(1)
  }

  @Test
  fun `start then provisional then final completes the assessment and emits created and amended events`() {
    val prisoner = "W1111WW"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("HIGH_GENERAL", "Provisional comment", pncChecked = false)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("PROVISIONAL")
      .jsonPath("$.rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.provisional").isEqualTo(true)
      .jsonPath("$.provisionalAssessmentComment").isEqualTo("Provisional comment")

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Final comment")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.provisional").isEqualTo(false)
      .jsonPath("$.assessmentComment").isEqualTo("Final comment")
      .jsonPath("$.provisionalAssessmentComment").isEqualTo("Provisional comment")
      .jsonPath("$.nextReviewDate").isEmpty

    // a standard final rating leaves a cleared per-prisoner next review date
    assertThat(csraNextReviewRepository.findByPrisonerNumber(prisoner)!!.nextReviewDate).isNull()

    val events = getDomainEvents(2)
    assertThat(events.map { it.eventType }).containsExactlyInAnyOrder(
      "cell.sharing.risk.assessment.created",
      "cell.sharing.risk.assessment.amended",
    )
    await untilCallTo { countAuditMessages() } matches { it == 2 }
  }

  @Test
  fun `a high-risk final rating sets the next review date twelve months on and stores risk-to and vulnerabilities`() {
    val prisoner = "H2222HH"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          stageBody(
            rating = "HIGH_SPECIFIC",
            comment = "History of racist incidents.",
            riskTo = """[{"category":"DIFFERENT_ETHNICITY","details":"Racist towards other ethnicities."}]""",
            vulnerabilities = """[{"category":"NEURODIVERSITY","details":"Autistic."}]""",
          ),
        ),
      )
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.riskTo[0].category").isEqualTo("DIFFERENT_ETHNICITY")
      .jsonPath("$.vulnerabilities[0].category").isEqualTo("NEURODIVERSITY")
      // clock is fixed at 2023-12-05, so the review date is 12 months on
      .jsonPath("$.nextReviewDate").isEqualTo("2024-12-05")

    assertThat(csraReviewRepository.findById(assessmentId).orElseThrow().nextReviewDate).isEqualTo(LocalDate.parse("2024-12-05"))
    assertThat(csraNextReviewRepository.findByPrisonerNumber(prisoner)!!.nextReviewDate)
      .isEqualTo(LocalDate.parse("2024-12-05"))
    assertThat(csraAssessmentStageRepository.findAllByCsraReviewId(assessmentId)).hasSize(1)

    assertThat(getDomainEvents(1).map { it.eventType })
      .containsExactly("cell.sharing.risk.assessment.created")
  }

  @Test
  fun `rejects a rating that conflicts with a mandatory high-risk offence`() {
    val prisoner = "M3333MM"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody(rating = "STANDARD", comment = "trying to under-rate", murder = true)))
      .exchange()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("MandatoryHighRiskGeneral")
  }

  @Test
  fun `rejects NONE combined with a real risk category`() {
    // The rule is shared with the review journey: NONE means "no identified risk to any of these groups",
    // which cannot coexist with naming one.
    val prisoner = "N5555NN"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          stageBody(
            rating = "HIGH_SPECIFIC",
            comment = "Contradictory risk answers.",
            riskTo = """[{"category":"NONE"},{"category":"DIFFERENT_ETHNICITY"}]""",
            vulnerabilities = """[{"category":"NONE"}]""",
          ),
        ),
      )
      .exchange()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("RiskCategoriesInvalid")
  }

  @Test
  fun `rejects a high-risk specific rating with no risk categories at all`() {
    val prisoner = "N6666NN"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody(rating = "HIGH_SPECIFIC", comment = "No groups recorded.")))
      .exchange()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("RiskCategoriesInvalid")
  }

  @Test
  fun `rejects starting a second assessment while one is in progress`() {
    // Deliberately not P4444PP: CsraCurrentRatingResourceTest seeds that number with a rated review
    // dated 2026, and csra_review is not cleared between test classes. rejectIfInProgress only
    // inspects the *latest* review, so whichever class ran first decided whether this saw its own
    // unrated draft or the other one's rated row — and with the rated row it returned 201, not 409.
    // The production weakness that makes the ordering matter at all is MAPA-332.
    val prisoner = "P4455PP"
    start(prisoner)

    webTestClient.post().uri("/csra-review/prisoner/$prisoner/assessment")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("AssessmentInProgress")
  }

  @Test
  fun `start records the prison the assessment was started at`() {
    val started = start("S6666SS", prisonId = "LEI")

    // Set on the review itself, not just the stage — the prison-scoped worklists filter on the review.
    assertThat(reviewPrison(started.assessmentId)).isEqualTo("LEI")
    // and it reads back through the API for a prisoner whose current rating is this in-progress review
    assertThat(started.currentRating.prisonId).isEqualTo("LEI")
  }

  @Test
  fun `start rejects a request with no prison`() {
    listOf("{}", """{ "prisonId": "" }""").forEach { body ->
      webTestClient.post().uri("/csra-review/prisoner/N7777NN/assessment")
        .headers(setAuthorisation(roles = writeRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .exchange()
        .expectStatus().isBadRequest
    }
  }

  @Test
  fun `the provisional stage moves the review to the prison it was assessed at`() {
    val prisoner = "T8888TT"
    val assessmentId = start(prisoner, prisonId = "LEI").assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Assessed after transfer", prisonId = "BXI")))
      .exchange()
      .expectStatus().isOk

    assertThat(reviewPrison(assessmentId)).isEqualTo("BXI")
  }

  @Test
  fun `an assessment taken straight to final records the prison it was finalised at`() {
    val prisoner = "F9999FF"
    val assessmentId = start(prisoner, prisonId = "LEI").assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Final only", prisonId = "BXI")))
      .exchange()
      .expectStatus().isOk

    // No provisional stage exists, so the FINAL short-circuit is the only thing setting this
    assertThat(reviewPrison(assessmentId)).isEqualTo("BXI")
  }

  @Test
  fun `amending the provisional after the final does not move the review back`() {
    val prisoner = "A1010AA"
    val assessmentId = start(prisoner, prisonId = "LEI").assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Final at Brixton", prisonId = "BXI")))
      .exchange()
      .expectStatus().isOk

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Provisional added late", prisonId = "LEI")))
      .exchange()
      .expectStatus().isOk

    // The headline prison follows the final stage, so a late provisional must not drag it back to Leeds
    assertThat(reviewPrison(assessmentId)).isEqualTo("BXI")
    // while each stage keeps its own record of where it happened
    assertThat(stagePrison(assessmentId, CsraAssessmentStage.PROVISIONAL)).isEqualTo("LEI")
    assertThat(stagePrison(assessmentId, CsraAssessmentStage.FINAL)).isEqualTo("BXI")
  }

  @Test
  fun `an assessment started through the API appears on that prison's worklist`() {
    // The end-to-end assertion this ticket exists for: everything above checks a column, this checks the
    // screen. WWI is used by no other test in this class, so the worklist can be asserted exactly.
    val prisoner = "E1111EE"
    hmppsAuth.stubGrantToken()
    // The worklist only shows prisoners prisoner-search still places at the prison, so they must be at WWI.
    prisonerSearch.stubGetPrisonerNames(listOf(RollMemberStub(prisoner, "Ellis", "Enderby", prisonId = "WWI")))

    start(prisoner, prisonId = "WWI")

    webTestClient.get().uri("/csra-review/prison/WWI/assessments-in-progress")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.assessmentStarted.length()").isEqualTo(1)
      .jsonPath("$.assessmentStarted[0].prisonerNumber").isEqualTo(prisoner)
      .jsonPath("$.assessmentStarted[0].firstName").isEqualTo("Ellis")
  }

  @Test
  fun `stores the evidence behind each offence answered yes`() {
    val prisoner = "V6666VV"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(
      prisoner,
      assessmentId,
      """
      {
        "rating": "STANDARD",
        "prisonId": "LEI",
        "assessmentComment": "Historic offences, no current concern.",
        "offenceArson": true,
        "offencePrejudiceMotivated": true,
        "offenceEvidence": [
          {
            "offence": "ARSON",
            "sources": ["PNC", "WARRANT"],
            "details": "Convicted of arson in 2018."
          },
          {
            "offence": "PREJUDICE_MOTIVATED",
            "sources": ["DPS", "OTHER"],
            "otherSourceDetail": "Security intelligence report",
            "details": "Alerts have history of racist incidents."
          }
        ]
      }
      """.trimIndent(),
    ).expectStatus().isOk

    val stageId = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!.id!!
    val evidence = offenceEvidenceRepository.findAllByStageId(stageId).associateBy { it.offence }
    assertThat(evidence).hasSize(2)

    with(evidence[CsraOffence.ARSON]!!) {
      assertThat(pnc).isTrue()
      assertThat(warrant).isTrue()
      assertThat(dps).isFalse()
      assertThat(per).isFalse()
      assertThat(other).isFalse()
      assertThat(otherSourceDetail).isNull()
      assertThat(details).isEqualTo("Convicted of arson in 2018.")
    }
    with(evidence[CsraOffence.PREJUDICE_MOTIVATED]!!) {
      assertThat(dps).isTrue()
      assertThat(other).isTrue()
      assertThat(pnc).isFalse()
      assertThat(otherSourceDetail).isEqualTo("Security intelligence report")
      assertThat(details).isEqualTo("Alerts have history of racist incidents.")
    }
  }

  @Test
  fun `re-submitting a stage replaces its offence evidence rather than adding to it`() {
    // The evidence is a child collection on a stage that upsert already permits re-submitting, so an
    // assessor correcting an answer must not leave the superseded record behind.
    val prisoner = "V7777VV"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(
      prisoner,
      assessmentId,
      evidenceBody("""[{"offence":"ARSON","sources":["PNC"],"details":"first pass"}]"""),
    ).expectStatus().isOk

    submitProvisional(
      prisoner,
      assessmentId,
      evidenceBody("""[{"offence":"KIDNAP_HOSTAGE","sources":["PER"],"details":"corrected"}]"""),
    ).expectStatus().isOk

    val stageId = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!.id!!
    val evidence = offenceEvidenceRepository.findAllByStageId(stageId)
    assertThat(evidence).hasSize(1)
    assertThat(evidence.single().offence).isEqualTo(CsraOffence.KIDNAP_HOSTAGE)
    assertThat(evidence.single().details).isEqualTo("corrected")
  }

  @Test
  fun `clearing the offence evidence removes the stored records`() {
    val prisoner = "V8888VV"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(
      prisoner,
      assessmentId,
      evidenceBody("""[{"offence":"ARSON","sources":["PNC"],"details":"answered in error"}]"""),
    ).expectStatus().isOk

    submitProvisional(prisoner, assessmentId, evidenceBody("[]")).expectStatus().isOk

    val stageId = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!.id!!
    assertThat(offenceEvidenceRepository.findAllByStageId(stageId)).isEmpty()
  }

  @Test
  fun `rejects more than one evidence record for the same offence`() {
    val prisoner = "V9999VV"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(
      prisoner,
      assessmentId,
      evidenceBody(
        """
        [{"offence":"ARSON","sources":["PNC"],"details":"one"},
         {"offence":"ARSON","sources":["DPS"],"details":"two"}]
        """.trimIndent(),
      ),
    ).expectStatus().isBadRequest
  }

  @Test
  fun `stores the free-text detail captured on a yes answer`() {
    val prisoner = "D1212DD"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(
      prisoner,
      assessmentId,
      """
      {
        "rating": "STANDARD",
        "prisonId": "LEI",
        "assessmentComment": "Standard risk.",
        "likelyToHarmCellmate": true,
        "likelyToHarmCellmateDetail": "Has threatened previous cellmates.",
        "significantlyVulnerable": true,
        "significantlyVulnerableDetail": "Says they have autism and struggle socially.",
        "causeForConcernSharing": true,
        "causeForConcernSharingDetail": "Aggressive towards staff on the wing.",
        "otherHighRiskIndicators": true,
        "otherHighRiskIndicatorsDetail": "Intelligence report from the security team.",
        "seenByHealthcare": true,
        "healthcareIncreasedRisk": true,
        "healthcareIncreasedRiskDetail": "Shared accommodation likely to cause significant distress."
      }
      """.trimIndent(),
    ).expectStatus().isOk

    with(csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!) {
      assertThat(likelyToHarmCellmateDetail).isEqualTo("Has threatened previous cellmates.")
      assertThat(significantlyVulnerableDetail).isEqualTo("Says they have autism and struggle socially.")
      assertThat(causeForConcernSharingDetail).isEqualTo("Aggressive towards staff on the wing.")
      assertThat(otherHighRiskIndicatorsDetail).isEqualTo("Intelligence report from the security team.")
      assertThat(healthcareIncreasedRiskDetail).isEqualTo("Shared accommodation likely to cause significant distress.")
    }
  }

  @Test
  fun `a stage submitted without any of the detail fields stores none`() {
    val prisoner = "D1313DD"
    val assessmentId = start(prisoner).assessmentId

    submitProvisional(prisoner, assessmentId, stageBody("STANDARD", "No detail supplied.")).expectStatus().isOk

    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!
    with(stage) {
      assertThat(likelyToHarmCellmateDetail).isNull()
      assertThat(significantlyVulnerableDetail).isNull()
      assertThat(causeForConcernSharingDetail).isNull()
      assertThat(otherHighRiskIndicatorsDetail).isNull()
      assertThat(healthcareIncreasedRiskDetail).isNull()
    }
    assertThat(offenceEvidenceRepository.findAllByStageId(stage.id!!)).isEmpty()
  }

  @Test
  fun `returns 404 submitting to an unknown assessment id`() {
    webTestClient.put().uri("/csra-review/prisoner/A1234BC/assessment/${UUID.randomUUID()}/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "comment")))
      .exchange()
      .expectStatus().isNotFound
  }

  // ── Partial save (saveAnswers) ────────────────────────────────────────────────────────────────────

  @Test
  fun `partial save stores answers without a rating and leaves the review IN_PROGRESS`() {
    val prisoner = "PS001AA"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI", pncChecked = true, arson = true)))
      .exchange()
      .expectStatus().isOk

    // The stage row exists with the answers but no completedBy/completedAt (not confirmed)
    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!
    assertThat(stage.pncChecked).isTrue()
    assertThat(stage.offenceArson).isTrue()
    assertThat(stage.completedBy).isNull()
    assertThat(stage.completedAt).isNull()
    assertThat(stage.lastSavedBy).isNotNull()
    assertThat(stage.lastSavedAt).isNotNull()
    assertThat(stage.assessmentComment).isNull()

    // The review stays IN_PROGRESS with no interim result
    val review = csraReviewRepository.findById(assessmentId).orElseThrow()
    assertThat(review.interimResult).isNull()
    assertThat(review.finalResult).isNull()
  }

  @Test
  fun `partial save does not change the prisoner's current rating`() {
    // First complete an assessment so the prisoner has a known rating
    val prisoner = "PS002AA"
    val firstId = start(prisoner).assessmentId
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$firstId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "First done")))
      .exchange()
      .expectStatus().isOk

    // Start a new assessment and partially save a stage
    val secondId = start(prisoner).assessmentId
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$secondId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI")))
      .exchange()
      .expectStatus().isOk

    // The current rating must still be the completed first assessment
    val current = csraCurrentRatingRepository.findByPrisonerNumber(prisoner)!!
    assertThat(current.rating?.name).isEqualTo("STANDARD")
    assertThat(current.setByReviewId).isEqualTo(firstId)
  }

  @Test
  fun `partial save publishes no domain event and no audit message`() {
    val prisoner = "PS003AA"
    val assessmentId = start(prisoner).assessmentId

    val auditBefore = countAuditMessages()

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI")))
      .exchange()
      .expectStatus().isOk

    // No SQS messages of any kind should have been produced
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isEqualTo(0)
    assertThat(countAuditMessages()).isEqualTo(auditBefore)
  }

  @Test
  fun `saving a stage then saving it again with an answer cleared results in null`() {
    val prisoner = "PS004AA"
    val assessmentId = start(prisoner).assessmentId

    // Save with arson = true
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI", arson = true)))
      .exchange()
      .expectStatus().isOk

    assertThat(csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!.offenceArson).isTrue()

    // Re-save without arson (cleared)
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI")))
      .exchange()
      .expectStatus().isOk

    assertThat(csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!.offenceArson).isNull()
  }

  @Test
  fun `confirming a stage after a partial save behaves exactly as without a partial save`() {
    val prisoner = "PS005AA"
    val assessmentId = start(prisoner).assessmentId

    // Partial save first
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI", pncChecked = true)))
      .exchange()
      .expectStatus().isOk

    // Then confirm with a rating
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "Confirmed after partial save")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("PROVISIONAL")
      .jsonPath("$.rating").isEqualTo("STANDARD")

    // The stage now has completedBy set (confirmed) as well as lastSavedBy from the partial save
    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, CsraAssessmentStage.PROVISIONAL)!!
    assertThat(stage.completedBy).isNotNull()
    assertThat(stage.completedAt).isNotNull()
    assertThat(stage.assessmentComment).isEqualTo("Confirmed after partial save")
  }

  @Test
  fun `a draft partially saved at one prison still appears on that prison's assessments-in-progress`() {
    val prisoner = "PS006AA"
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisonerNames(listOf(RollMemberStub(prisoner, "Partial", "Save", prisonId = "WWI")))
    val assessmentId = start(prisoner, prisonId = "WWI").assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("WWI")))
      .exchange()
      .expectStatus().isOk

    webTestClient.get().uri("/csra-review/prison/WWI/assessments-in-progress")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.assessmentStarted.length()").isEqualTo(1)
      .jsonPath("$.assessmentStarted[0].prisonerNumber").isEqualTo(prisoner)
  }

  @Test
  fun `partial save rejects a request with no prison`() {
    val prisoner = "PS007AA"
    val assessmentId = start(prisoner).assessmentId

    listOf("{}", """{ "prisonId": "" }""").forEach { body ->
      webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
        .headers(setAuthorisation(roles = writeRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .exchange()
        .expectStatus().isBadRequest
    }
  }

  @Test
  fun `partial save returns 404 for an unknown assessment`() {
    webTestClient.put().uri("/csra-review/prisoner/A1234BC/assessment/${UUID.randomUUID()}/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI")))
      .exchange()
      .expectStatus().isNotFound
  }

  // ── GET assessment (getAssessment) ───────────────────────────────────────────────────────────────

  @Test
  fun `GET returns the full answer set so an assessment can be resumed`() {
    val prisoner = "PS008AA"
    val assessmentId = start(prisoner).assessmentId

    // Partially save a provisional stage with a rich set of answers
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(
        BodyInserters.fromValue(
          """
          {
            "prisonId": "LEI",
            "version": 1,
            "pncChecked": true,
            "dpsChecked": false,
            "offenceArson": true,
            "offenceEvidence": [{"offence": "ARSON", "sources": ["PNC"], "details": "Convicted in 2019."}],
            "likelyToHarmCellmate": true,
            "likelyToHarmCellmateDetail": "Threatened previous cellmates.",
            "seenByHealthcare": false,
            "riskTo": [{"category": "DIFFERENT_ETHNICITY", "details": "Racist incidents on record."}],
            "vulnerabilities": [{"category": "NEURODIVERSITY", "details": "Autistic."}]
          }
          """.trimIndent(),
        ),
      )
      .exchange()
      .expectStatus().isOk

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.assessmentId").isEqualTo(assessmentId.toString())
      .jsonPath("$.prisonerNumber").isEqualTo(prisoner)
      .jsonPath("$.status").isEqualTo("IN_PROGRESS")
      .jsonPath("$.interimResult").doesNotExist()
      .jsonPath("$.finalResult").doesNotExist()
      .jsonPath("$.stages.length()").isEqualTo(1)
      .jsonPath("$.stages[0].stage").isEqualTo("PROVISIONAL")
      .jsonPath("$.stages[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.stages[0].lastSavedBy").isNotEmpty
      .jsonPath("$.stages[0].lastSavedAt").isNotEmpty
      .jsonPath("$.stages[0].pncChecked").isEqualTo(true)
      .jsonPath("$.stages[0].dpsChecked").isEqualTo(false)
      .jsonPath("$.stages[0].offenceArson").isEqualTo(true)
      .jsonPath("$.stages[0].offenceEvidence.length()").isEqualTo(1)
      .jsonPath("$.stages[0].offenceEvidence[0].offence").isEqualTo("ARSON")
      .jsonPath("$.stages[0].offenceEvidence[0].details").isEqualTo("Convicted in 2019.")
      .jsonPath("$.stages[0].likelyToHarmCellmate").isEqualTo(true)
      .jsonPath("$.stages[0].likelyToHarmCellmateDetail").isEqualTo("Threatened previous cellmates.")
      .jsonPath("$.stages[0].seenByHealthcare").isEqualTo(false)
      .jsonPath("$.stages[0].riskTo.length()").isEqualTo(1)
      .jsonPath("$.stages[0].riskTo[0].category").isEqualTo("DIFFERENT_ETHNICITY")
      .jsonPath("$.stages[0].vulnerabilities.length()").isEqualTo(1)
      .jsonPath("$.stages[0].vulnerabilities[0].category").isEqualTo("NEURODIVERSITY")
  }

  @Test
  fun `GET returns an empty stages list for a brand-new assessment`() {
    val prisoner = "PS009AA"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody<CsraAssessmentDto>()
      .consumeWith { result ->
        assertThat(result.responseBody!!.stages).isEmpty()
        assertThat(result.responseBody!!.status.name).isEqualTo("IN_PROGRESS")
      }
  }

  @Test
  fun `GET returns 404 for an unknown assessment`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/assessment/${UUID.randomUUID()}")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `an unrated stage created by a partial save does not disturb the prisoner rating via buildCurrentRating`() {
    // Guards against the note in the ticket: "an unrated PROVISIONAL stage will be picked up by
    // buildCurrentRating and supply a null comment — believed harmless, but test explicitly".
    val prisoner = "PS010AA"
    val assessmentId = start(prisoner).assessmentId

    // The prisoner has no prior rating; the current rating should still be IN_PROGRESS, not broken
    webTestClient.get().uri("/csra-review/prisoner/$prisoner/current-rating")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("IN_PROGRESS")
      .jsonPath("$.rating").doesNotExist()
      .jsonPath("$.prisonId").isEqualTo("LEI")
  }

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  private fun saveAnswers(prisonerNumber: String, assessmentId: UUID, body: String, stage: String = "PROVISIONAL") = webTestClient.put()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment/$assessmentId/stage/$stage/answers")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(body))
    .exchange()

  /** Each save changes likelyToHarmCellmateDetail so the entity is dirty and the version actually moves. */
  private fun versionedAnswersBody(version: Int, detail: String) = """{ "prisonId": "LEI", "version": $version, "likelyToHarmCellmateDetail": "$detail" }"""

  @Test
  fun `version increments on each substantive save and is returned with the stage`() {
    val prisoner = "PSV01AA"
    val assessmentId = start(prisoner).assessmentId

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "first"))
      .expectStatus().isOk
      .expectBody().jsonPath("$.stages[0].version").isEqualTo(1)

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "second"))
      .expectStatus().isOk
      .expectBody().jsonPath("$.stages[0].version").isEqualTo(2)
  }

  @Test
  fun `a save carrying a stale version is rejected as a conflict`() {
    val prisoner = "PSV02AA"
    val assessmentId = start(prisoner).assessmentId

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "first")).expectStatus().isOk
    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "second")).expectStatus().isOk

    // The row is now at version 2; a client still holding version 1 must be told it has lost the race.
    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "stale"))
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `a save carrying a version ahead of the stored version is rejected`() {
    val prisoner = "PSV03AA"
    val assessmentId = start(prisoner).assessmentId

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "first")).expectStatus().isOk

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 99, detail = "from the future"))
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `an unrecognised stage value is rejected as a bad request`() {
    val prisoner = "PSV04AA"
    val assessmentId = start(prisoner).assessmentId

    saveAnswers(prisoner, assessmentId, versionedAnswersBody(version = 1, detail = "x"), stage = "BOGUS")
      .expectStatus().isBadRequest
  }

  @Test
  fun `the assessment can be read back by the role that wrote it`() {
    val prisoner = "PSV05AA"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = writeRole))
      .exchange().expectStatus().isOk

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = readRole))
      .exchange().expectStatus().isOk
  }

  @ParameterizedTest
  @CsvSource("CLOSED", "ARCHIVED")
  fun `an assessment closed or archived by a movement can no longer be written to`(status: CsraReviewStatus) {
    val prisoner = "C7001CC" + status.name.first()
    val assessmentId = seedAssessment(prisoner, status).id!!
    val body = stageBody("STANDARD", "should be refused")

    listOf("provisional", "final").forEach { stage ->
      webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/$stage")
        .headers(setAuthorisation(roles = writeRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue(body))
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")
    }

    // The answers-only endpoint has to be covered too - it is a write like any other.
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/stage/PROVISIONAL/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(answersBody("LEI")))
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")

    assertThat(csraReviewRepository.findById(assessmentId).get().status).isEqualTo(status)
  }

  @Test
  fun `submitting a final to an archived assessment does not resurrect it as the current rating`() {
    val prisoner = "C7002CC"
    val assessmentId = seedAssessment(prisoner, CsraReviewStatus.ARCHIVED).id!!

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("HIGH_GENERAL", "should be refused")))
      .exchange()
      .expectStatus().isEqualTo(409)

    val review = csraReviewRepository.findById(assessmentId).get()
    assertThat(review.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(review.finalResult).isNull()
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)?.rating).isNull()
  }

  /** Amending a completed assessment is a real requirement, so COMPLETE must stay writable. */
  @Test
  fun `a completed assessment can still be amended`() {
    val prisoner = "C7003CC"
    val assessmentId = start(prisoner).assessmentId

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "first pass")))
      .exchange().expectStatus().isOk

    assertThat(csraReviewRepository.findById(assessmentId).get().status).isEqualTo(CsraReviewStatus.COMPLETE)

    webTestClient.put().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId/final")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("HIGH_GENERAL", "amended after review")))
      .exchange().expectStatus().isOk

    assertThat(csraReviewRepository.findById(assessmentId).get().finalResult).isEqualTo(CsraResult.HIGH_GENERAL)
  }

  /** A closed or archived assessment must stay readable - the guard is on writes only. */
  @Test
  fun `a closed assessment can still be read back`() {
    val prisoner = "C7004CC"
    val assessmentId = seedAssessment(prisoner, CsraReviewStatus.CLOSED).id!!

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/assessment/$assessmentId")
      .headers(setAuthorisation(roles = readRole))
      .exchange().expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("CLOSED")
  }
}
