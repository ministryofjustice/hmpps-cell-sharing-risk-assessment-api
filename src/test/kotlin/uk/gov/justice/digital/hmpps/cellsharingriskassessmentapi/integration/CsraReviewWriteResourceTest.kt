package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageEvidenceSourceRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CsraReviewWriteResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var evidenceSourceRepository: CsraAssessmentStageEvidenceSourceRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  private val auditQueue by lazy { hmppsQueueService.findByQueueId("audit") as HmppsQueue }

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
  }

  private fun startBody(prisonId: String) = """{ "prisonId": "$prisonId" }"""

  /**
   * A complete STANDARD review body. Every question is answered No so that no detail is required, which
   * keeps the tests that are not about detail validation short.
   */
  private fun stageBody(
    rating: String = "STANDARD",
    comment: String = "Reviewed. No further concerns.",
    prisonId: String = "LEI",
    reason: String = "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING",
    chair: String = "Sue Carter",
    evidenceSources: String = """[{"source":"OASYS"}]""",
    murder: Boolean = false,
    murderDetail: String? = null,
    riskTo: String = "[]",
    vulnerabilities: String = "[]",
    nextReviewDate: String? = null,
  ) = """
    {
      "rating": "$rating",
      "prisonId": "$prisonId",
      "reviewComment": "$comment",
      "reviewReason": "$reason",
      "mdtChairName": "$chair",
      "evidenceSources": $evidenceSources,
      "offenceMurderManslaughter": $murder,
      ${murderDetail?.let { """"offenceMurderManslaughterDetail": "$it",""" } ?: ""}
      "riskTo": $riskTo,
      "vulnerabilities": $vulnerabilities
      ${nextReviewDate?.let { ""","nextReviewDate": "$it"""" } ?: ""}
    }
  """.trimIndent()

  private fun start(prisonerNumber: String, prisonId: String = "LEI"): CsraReviewStarted = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/review")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(startBody(prisonId)))
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraReviewStarted>()
    .returnResult().responseBody!!

  private fun submit(prisonerNumber: String, reviewId: UUID, stage: String, body: String = stageBody()) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisonerNumber/review/$reviewId/$stage")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(body))
    .exchange()

  private fun review(reviewId: UUID) = csraReviewRepository.findById(reviewId).orElseThrow()

  private fun countAuditMessages() = auditQueue.sqsClient.countMessagesOnQueue(auditQueue.queueUrl).get()

  private fun seedInProgress(prisonerNumber: String, type: CsraType, status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = LocalDate.parse("2023-12-01"),
      type = type,
      status = status,
      createdAt = LocalDateTime.parse("2023-12-01T09:00:00"),
      createdBy = "SCARTER",
    ),
  )

  @Test
  fun `returns 401 without a token`() {
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/review")
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the read role`() {
    // Sends a valid body deliberately: @PreAuthorize fires after argument resolution, so an absent body
    // would 400 before the role was ever checked.
    webTestClient.post().uri("/csra-review/prisoner/A1234BC/review")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `starting a review creates an in-progress CSRA_REVIEW and returns its id`() {
    val prisoner = "R1111RR"

    val started = start(prisoner, prisonId = "LEI")

    val entity = review(started.reviewId)
    assertThat(entity.type).isEqualTo(CsraType.CSRA_REVIEW)
    assertThat(entity.status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
    assertThat(entity.prisonId).isEqualTo("LEI")
    assertThat(entity.finalResult).isNull()
    assertThat(entity.interimResult).isNull()
  }

  @Test
  fun `starting a review is rejected when an unrated assessment is already in progress`() {
    val prisoner = "R2222RR"
    seedInProgress(prisoner, CsraType.CSRA_INITIAL_REVIEW)

    webTestClient.post().uri("/csra-review/prisoner/$prisoner/review")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("AssessmentInProgress")
  }

  @Test
  fun `starting a review is rejected when another unrated review is already in progress`() {
    val prisoner = "R3333RR"
    seedInProgress(prisoner, CsraType.CSRA_REVIEW)

    webTestClient.post().uri("/csra-review/prisoner/$prisoner/review")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(startBody("LEI")))
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @ParameterizedTest
  @CsvSource("CLOSED", "ARCHIVED")
  fun `a review closed or archived by a movement can no longer be submitted to`(status: CsraReviewStatus) {
    val prisoner = "R7001RR" + status.name.first()
    val reviewId = seedInProgress(prisoner, CsraType.CSRA_REVIEW, status = status).id!!

    listOf("interim", "final").forEach { stage ->
      submit(prisoner, reviewId, stage)
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")
    }

    // The rejection is the point: nothing was written.
    assertThat(review(reviewId).status).isEqualTo(status)
    assertThat(review(reviewId).finalResult).isNull()
  }

  @Test
  fun `submitting a final to an archived review does not make it the current rating`() {
    val prisoner = "R7002RR"
    val reviewId = seedInProgress(prisoner, CsraType.CSRA_REVIEW, status = CsraReviewStatus.ARCHIVED).id!!

    submit(prisoner, reviewId, "final", stageBody(rating = "HIGH_GENERAL"))
      .expectStatus().isEqualTo(409)

    assertThat(review(reviewId).status).isEqualTo(CsraReviewStatus.ARCHIVED)
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)?.rating).isNull()
  }

  @Test
  fun `an archived review does not block starting a new one`() {
    val prisoner = "R4444RR"
    seedInProgress(prisoner, CsraType.CSRA_REVIEW, status = CsraReviewStatus.ARCHIVED)

    start(prisoner)
  }

  @Test
  fun `the interim stage sets the interim result and leaves the review in progress`() {
    val prisoner = "R5555RR"
    val reviewId = start(prisoner).reviewId

    submit(prisoner, reviewId, "interim")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.provisional").isEqualTo(true)

    val entity = review(reviewId)
    assertThat(entity.interimResult).isEqualTo(CsraResult.STANDARD)
    assertThat(entity.finalResult).isNull()
    assertThat(entity.status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
    assertThat(csraAssessmentStageRepository.findByCsraReviewIdAndStage(reviewId, CsraAssessmentStage.INTERIM)).isNotNull
  }

  @Test
  fun `the final stage completes the review and stores the whole answer set`() {
    val prisoner = "R6666RR"
    val reviewId = start(prisoner).reviewId

    submit(
      prisoner,
      reviewId,
      "final",
      stageBody(
        comment = "Reviewed after new information.",
        reason = "NEW_OR_ADDITIONAL_INFORMATION",
        chair = "Michael Stanley",
        evidenceSources = """[{"source":"OASYS"},{"source":"OTHER","details":"Wing intelligence report"}]""",
        murder = true,
        murderDetail = "Conviction confirmed on PNC.",
      ),
    )
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")

    val entity = review(reviewId)
    assertThat(entity.finalResult).isEqualTo(CsraResult.STANDARD)
    assertThat(entity.status).isEqualTo(CsraReviewStatus.COMPLETE)

    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(reviewId, CsraAssessmentStage.FINAL)!!
    assertThat(stage.reviewReason?.name).isEqualTo("NEW_OR_ADDITIONAL_INFORMATION")
    assertThat(stage.mdtChairName).isEqualTo("Michael Stanley")
    assertThat(stage.offenceMurderManslaughter).isTrue()
    assertThat(stage.offenceMurderManslaughterDetail).isEqualTo("Conviction confirmed on PNC.")
    // The review comment shares the assessment's column — the designs label it per journey.
    assertThat(stage.assessmentComment).isEqualTo("Reviewed after new information.")
    assertThat(stage.questionSetVersion).isEqualTo(1)
    // Read through the repository rather than the entity's lazy collection, which has no session here.
    val sources = evidenceSourceRepository.findAllByStageId(stage.id!!)
    assertThat(sources.map { it.source.name }).containsExactlyInAnyOrder("OASYS", "OTHER")
    assertThat(sources.first { it.source.name == "OTHER" }.details).isEqualTo("Wing intelligence report")
  }

  @Test
  fun `a high-risk final rating stores the reviewer's chosen next review date rather than computing one`() {
    val prisoner = "R7777RR"
    val reviewId = start(prisoner).reviewId

    // The clock is fixed at 2023-12-05. The assessment journey would compute 2024-12-05; the review takes
    // whatever the reviewer chose, which is the whole point of the divergence.
    submit(
      prisoner,
      reviewId,
      "final",
      stageBody(
        rating = "HIGH_SPECIFIC",
        comment = "History of racist incidents.",
        riskTo = """[{"category":"DIFFERENT_ETHNICITY","details":"Racist towards other ethnicities."}]""",
        vulnerabilities = """[{"category":"NEURODIVERSITY","details":"Autistic."}]""",
        nextReviewDate = "2024-03-01",
      ),
    )
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.nextReviewDate").isEqualTo("2024-03-01")

    assertThat(csraNextReviewRepository.findByPrisonerNumber(prisoner)!!.nextReviewDate)
      .isEqualTo(LocalDate.parse("2024-03-01"))
  }

  @Test
  fun `a standard final rating clears the next review date`() {
    val prisoner = "R8888RR"
    val reviewId = start(prisoner).reviewId

    submit(prisoner, reviewId, "final", stageBody(nextReviewDate = "2024-03-01"))
      .expectStatus().isOk

    assertThat(csraNextReviewRepository.findByPrisonerNumber(prisoner)!!.nextReviewDate).isNull()
  }

  @Test
  fun `rejects a next review date that is not in the future`() {
    val prisoner = "R9999RR"
    val reviewId = start(prisoner).reviewId

    // Today, on the fixed clock — a review date that is already due the moment it is saved.
    submit(prisoner, reviewId, "final", stageBody(nextReviewDate = "2023-12-05"))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("NextReviewDateInvalid")
  }

  @Test
  fun `rejects a yes answer with no details`() {
    val prisoner = "RD111RR"
    val reviewId = start(prisoner).reviewId

    submit(prisoner, reviewId, "interim", stageBody(murder = true))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("MissingAnswerDetail")
      .jsonPath("$.developerMessage").value<String> { assertThat(it).contains("offenceMurderManslaughter") }
  }

  @Test
  fun `rejects an OTHER evidence source with no details`() {
    val prisoner = "RD222RR"
    val reviewId = start(prisoner).reviewId

    submit(prisoner, reviewId, "interim", stageBody(evidenceSources = """[{"source":"OTHER"}]"""))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("MissingAnswerDetail")
  }

  @Test
  fun `accepts a mandatory high-risk offence with a standard rating, unlike an assessment`() {
    // The deliberate divergence: a review revisits an existing rating with more context, often precisely to
    // conclude a historic trigger offence no longer warrants high risk.
    val prisoner = "RM111RR"
    val reviewId = start(prisoner).reviewId

    submit(
      prisoner,
      reviewId,
      "final",
      stageBody(rating = "STANDARD", murder = true, murderDetail = "Historic conviction, no recent concerns."),
    )
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").isEqualTo("STANDARD")
  }

  @Test
  fun `rejects NONE combined with a real risk category`() {
    val prisoner = "RN111RR"
    val reviewId = start(prisoner).reviewId

    submit(
      prisoner,
      reviewId,
      "final",
      stageBody(
        rating = "HIGH_SPECIFIC",
        riskTo = """[{"category":"NONE"},{"category":"DIFFERENT_ETHNICITY"}]""",
        vulnerabilities = """[{"category":"NONE"}]""",
      ),
    )
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("RiskCategoriesInvalid")
  }

  @Test
  fun `rejects a high-risk specific rating with no risk categories at all`() {
    val prisoner = "RN222RR"
    val reviewId = start(prisoner).reviewId

    submit(prisoner, reviewId, "final", stageBody(rating = "HIGH_SPECIFIC"))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("RiskCategoriesInvalid")
  }

  @Test
  fun `accepts an explicit NONE answer on a high-risk specific rating`() {
    val prisoner = "RN333RR"
    val reviewId = start(prisoner).reviewId

    submit(
      prisoner,
      reviewId,
      "final",
      stageBody(
        rating = "HIGH_SPECIFIC",
        riskTo = """[{"category":"NONE"}]""",
        vulnerabilities = """[{"category":"NONE"}]""",
        nextReviewDate = "2024-06-01",
      ),
    )
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.riskTo[0].category").isEqualTo("NONE")
  }

  @Test
  fun `returns 404 for an assessment id submitted to the review endpoint`() {
    val prisoner = "RX111RR"
    val assessment = seedInProgress(prisoner, CsraType.CSRA_INITIAL_REVIEW)

    submit(prisoner, assessment.id!!, "interim")
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CsraReviewNotFound")
  }

  @Test
  fun `publishes created then amended, and nothing while the review is unrated`() {
    val prisoner = "RE111RR"
    val reviewId = start(prisoner).reviewId

    // Starting a review records no rating, so there is nothing for a consumer to see yet.
    assertThat(getDomainEvents(0)).isEmpty()

    submit(prisoner, reviewId, "interim").expectStatus().isOk
    submit(prisoner, reviewId, "final").expectStatus().isOk

    assertThat(getDomainEvents(2).map { it.eventType })
      .containsExactly(
        "cell.sharing.risk.assessment.created",
        "cell.sharing.risk.assessment.amended",
      )
    // Starting is audited even though it is not published, so three audit messages for two ratings.
    await untilCallTo { countAuditMessages() } matches { it == 2 }
  }

  @Test
  fun `a review started through the API appears on that prison's reviews-in-progress worklist`() {
    // The end-to-end assertion this ticket exists for: until now nothing could create a CSRA_REVIEW, so the
    // worklist was permanently empty. WWI is used by no other test here, so it can be asserted exactly.
    val prisoner = "RW111RR"
    // The worklist drops anyone prisoner-search no longer places at the prison (MAPA-223).
    prisonerSearch.stubGetPrisonerNames(listOf(RollMemberStub(prisoner, "Simon", "Kettleby", prisonId = "WWI")))

    start(prisoner, prisonId = "WWI")

    webTestClient.get().uri("/csra-review/prison/WWI/reviews-in-progress")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(1)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo(prisoner)
      .jsonPath("$.content[0].firstName").isEqualTo("Simon")
  }
}
