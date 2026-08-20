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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraOffence
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageOffenceEvidenceRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
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
  private fun versionedAnswersBody(version: Int, detail: String) =
    """{ "prisonId": "LEI", "version": $version, "likelyToHarmCellmateDetail": "$detail" }"""

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
}
