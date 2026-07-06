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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
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

  private fun start(prisonerNumber: String): CsraCurrentRating = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment")
    .headers(setAuthorisation(roles = writeRole))
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraCurrentRating>()
    .returnResult().responseBody!!

  private fun countAuditMessages() = auditQueue.sqsClient.countMessagesOnQueue(auditQueue.queueUrl).get()

  @Test
  fun `returns 401 without a token`() {
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/assessment")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/assessment")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `start creates an in-progress draft that records who started it`() {
    val started = start("W0000WW")

    assertThat(started.status).isEqualTo(CsraRatingStatus.IN_PROGRESS)
    assertThat(started.rating).isNull()
    assertThat(started.startedBy).isNotNull()
    assertThat(started.startedAt).isNotNull()
    assertThat(started.reviewId).isNotNull()
  }

  @Test
  fun `start then provisional then final completes the assessment and emits created and amended events`() {
    val prisoner = "W1111WW"
    val assessmentId = start(prisoner).reviewId!!

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
    val assessmentId = start(prisoner).reviewId!!

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
    val assessmentId = start(prisoner).reviewId!!

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
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("AssessmentInProgress")
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
