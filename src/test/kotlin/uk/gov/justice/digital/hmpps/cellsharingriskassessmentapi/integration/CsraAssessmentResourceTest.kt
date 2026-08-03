package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.time.LocalDate
import java.util.UUID

class CsraAssessmentResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  private val auditQueue by lazy { hmppsQueueService.findByQueueId("audit") as HmppsQueue }

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

  private fun start(prisonerNumber: String, prisonId: String = "LEI"): CsraAssessmentStarted = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(startBody(prisonId)))
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraAssessmentStarted>()
    .returnResult().responseBody!!

  private fun reviewPrison(assessmentId: UUID) = csraReviewRepository.findById(assessmentId).orElseThrow().prisonId

  private fun stagePrison(assessmentId: UUID, stage: CsraAssessmentStage) = csraAssessmentStageRepository.findByCsraReviewIdAndStage(assessmentId, stage)!!.prisonId

  private fun countAuditMessages() = auditQueue.sqsClient.countMessagesOnQueue(auditQueue.queueUrl).get()

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
  fun `rejects starting a second assessment while one is in progress`() {
    val prisoner = "P4444PP"
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
    prisonerSearch.stubGetPrisonerNames(listOf(RollMemberStub(prisoner, "Ellis", "Enderby")))

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
  fun `returns 404 submitting to an unknown assessment id`() {
    webTestClient.put().uri("/csra-review/prisoner/A1234BC/assessment/${UUID.randomUUID()}/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody("STANDARD", "comment")))
      .exchange()
      .expectStatus().isNotFound
  }
}
