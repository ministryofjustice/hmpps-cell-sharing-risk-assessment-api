package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Cancelling a review that has no rating yet (MAPA-537), which follows cancelling an initial assessment (MAPA-415). */
class CsraReviewCancelResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    switchOn("LEI", "BXI")
  }

  private fun start(prisoner: String, withAnswers: Boolean = true): UUID = webTestClient.post()
    .uri("/csra-review/prisoner/$prisoner/review")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(
      if (withAnswers) {
        """{ "prisonId": "LEI", "answers": { "reviewReason": "NEW_OR_ADDITIONAL_INFORMATION", "mdtReviewed": true, "evidenceSources": [{"source":"OASYS"}] } }"""
      } else {
        """{ "prisonId": "LEI" }"""
      },
    )
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraReviewStarted>()
    .returnResult().responseBody!!
    .reviewId

  private fun cancel(prisoner: String, reviewId: UUID, prisonId: String = "LEI", roles: List<String> = writeRole) = webTestClient.post()
    .uri("/csra-review/prisoner/$prisoner/review/$reviewId/cancel")
    .headers(setAuthorisation(user = "CANCELLER", roles = roles))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "$prisonId" }""")
    .exchange()

  private fun confirm(prisoner: String, reviewId: UUID, stage: String, reason: String, rating: String) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisoner/review/$reviewId/$stage")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(
      """
      {
        "rating": "$rating",
        "prisonId": "LEI",
        "reviewComment": "Reviewed.",
        "reviewReason": "$reason",
        "mdtChairName": "Sue Carter",
        "evidenceSources": [{"source":"OASYS"}]
        ${ReviewAnswersFixture.completeAnswerFields(reason)}
      }
      """.trimIndent(),
    )
    .exchange()

  private fun review(reviewId: UUID) = csraReviewRepository.findById(reviewId).orElseThrow()

  @Test
  fun `cancelling an unrated review archives it, keeps its answers out of sight, and allows a new review`() {
    val prisoner = "K7001KK"
    val reviewId = start(prisoner)

    cancel(prisoner, reviewId, prisonId = "BXI")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").doesNotExist()
      .jsonPath("$.inProgress").doesNotExist()

    val cancelled = review(reviewId)
    assertThat(cancelled.status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(cancelled.closureReason).isEqualTo(CsraClosureReason.CANCELLED)
    assertThat(cancelled.closedAt).isEqualTo(LocalDateTime.now(clock))
    assertThat(cancelled.closedBy).isEqualTo("CANCELLER")
    // The answers are kept...
    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(reviewId, CsraAssessmentStage.FINAL)!!
    assertThat(stage.reviewReason?.name).isEqualTo("NEW_OR_ADDITIONAL_INFORMATION")
    // ...but the review can no longer be read, saved to or found on the prison's list.
    webTestClient.get().uri("/csra-review/prisoner/$prisoner/review/$reviewId")
      .headers(setAuthorisation(roles = writeRole))
      .exchange().expectStatus().isNotFound
    webTestClient.put().uri("/csra-review/prisoner/$prisoner/review/$reviewId/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "prisonId": "LEI", "version": ${stage.version}, "answers": { "reviewReason": "NEW_OR_ADDITIONAL_INFORMATION" } }""")
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody().jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")
    assertThat(
      csraReviewRepository.findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus("LEI", CsraType.CSRA_REVIEW, CsraReviewStatus.IN_PROGRESS)
        .map { it.id },
    ).doesNotContain(reviewId)

    // A repeat, such as a retry, succeeds without changing the record or auditing again.
    cancel(prisoner, reviewId, prisonId = "LEI").expectStatus().isOk
    assertThat(review(reviewId).closedAt).isEqualTo(cancelled.closedAt)

    val audit = getAuditMessages(1).single()
    assertThat(audit.path("what").asText()).isEqualTo("CSRA_CANCELLED")
    assertThat(audit.path("who").asText()).isEqualTo("CANCELLER")
    val details = objectMapper.readTree(audit.path("details").asText())
    assertThat(details.path("review").path("id").asText()).isEqualTo(reviewId.toString())
    assertThat(details.path("requestPrisonId").asText()).isEqualTo("BXI")
    // An unrated review was never published, so its cancellation is not either.
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()

    assertThat(start(prisoner)).isNotEqualTo(reviewId)
  }

  @Test
  fun `cancelling leaves the prisoner's existing rating in place`() {
    val prisoner = "K7002KK"
    val earlierReviewId = start(prisoner, withAnswers = false)
    confirm(prisoner, earlierReviewId, "final", "SHORT_TERM_HIGH_RISK_REVIEW", "STANDARD").expectStatus().isOk
    getDomainEvents(1)
    val reviewId = start(prisoner)

    cancel(prisoner, reviewId)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.reviewId").isEqualTo(earlierReviewId.toString())
      .jsonPath("$.inProgress").doesNotExist()
    assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
  }

  @Test
  fun `a review with an interim rating cannot be cancelled, and can still be completed`() {
    val prisoner = "K7003KK"
    val reviewId = start(prisoner)
    confirm(prisoner, reviewId, "interim", "NEW_OR_ADDITIONAL_INFORMATION", "HIGH_GENERAL").expectStatus().isOk

    cancel(prisoner, reviewId)
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CsraReviewNotCancellable")
      .jsonPath("$.userMessage").isEqualTo("Conflict: CSRA review $reviewId has a rating and cannot be cancelled")
    assertThat(review(reviewId).status).isEqualTo(CsraReviewStatus.IN_PROGRESS)

    confirm(prisoner, reviewId, "final", "NEW_OR_ADDITIONAL_INFORMATION", "HIGH_GENERAL").expectStatus().isOk
  }

  @Test
  fun `a completed review cannot be cancelled`() {
    val prisoner = "K7004KK"
    val reviewId = start(prisoner, withAnswers = false)
    confirm(prisoner, reviewId, "final", "SHORT_TERM_HIGH_RISK_REVIEW", "STANDARD").expectStatus().isOk

    cancel(prisoner, reviewId)
      .expectStatus().isEqualTo(409)
      .expectBody().jsonPath("$.errorCode").isEqualTo("CsraReviewNotCancellable")
    assertThat(review(reviewId).status).isEqualTo(CsraReviewStatus.COMPLETE)
  }

  @ParameterizedTest
  @CsvSource("K7005KC, CLOSED, STANDARD", "K7005KA, ARCHIVED, ")
  fun `a review a prisoner movement has closed or archived cannot be cancelled`(prisoner: String, status: CsraReviewStatus, interim: CsraResult?) {
    val reviewId = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisoner,
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2023-12-01"),
        type = CsraType.CSRA_REVIEW,
        status = status,
        createdAt = LocalDateTime.parse("2023-12-01T09:00:00"),
        createdBy = "SCARTER",
      ).apply {
        interimResult = interim
        closureReason = CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER
      },
    ).id!!

    cancel(prisoner, reviewId)
      .expectStatus().isEqualTo(409)
      .expectBody().jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")
    assertThat(review(reviewId).closureReason).isEqualTo(CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER)
  }

  @Test
  fun `an unknown id, another prisoner's review and an assessment are all 404`() {
    val prisoner = "K7006KK"
    cancel(prisoner, UUID.randomUUID()).expectStatus().isNotFound

    val reviewId = start(prisoner)
    cancel("K7006KX", reviewId).expectStatus().isNotFound

    val assessmentId = webTestClient.post().uri("/csra-review/prisoner/K7006KA/assessment")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "prisonId": "LEI" }""")
      .exchange()
      .expectStatus().isCreated
      .expectBody().returnResult().responseBody!!
      .let { objectMapper.readTree(it).path("assessmentId").asText() }
    cancel("K7006KA", UUID.fromString(assessmentId)).expectStatus().isNotFound

    assertThat(review(reviewId).status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
  }

  @Test
  fun `cancelling needs the write role and a prison`() {
    val prisoner = "K7007KK"
    val reviewId = start(prisoner)

    cancel(prisoner, reviewId, roles = listOf("ROLE_CSRA_REVIEW__R")).expectStatus().isForbidden
    cancel(prisoner, reviewId, prisonId = "").expectStatus().isBadRequest

    assertThat(review(reviewId).status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
  }
}
