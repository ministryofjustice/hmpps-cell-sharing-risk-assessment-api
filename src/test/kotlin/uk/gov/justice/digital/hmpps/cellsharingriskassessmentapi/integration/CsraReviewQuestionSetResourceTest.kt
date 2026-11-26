package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraReviewQuestion
import java.util.UUID

/**
 * Each reason for a review asks its own questions (MAPA-537), and a final rating is only accepted once the
 * review is complete for its reason. The expected question sets come from [ReviewAnswersFixture], which is
 * written out from the designs.
 */
class CsraReviewQuestionSetResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    switchOn("LEI")
  }

  private fun start(prisoner: String): UUID = webTestClient.post()
    .uri("/csra-review/prisoner/$prisoner/review")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "LEI" }""")
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraReviewStarted>()
    .returnResult().responseBody!!
    .reviewId

  /** A final confirm that is complete for [reason], apart from what [answers] changes. */
  private fun finalBody(
    reason: String,
    rating: String = "STANDARD",
    answers: Map<String, Any?> = emptyMap(),
    evidenceSources: String = """[{"source":"OASYS"}]""",
  ) = """
    {
      "rating": "$rating",
      "prisonId": "LEI",
      "reviewComment": "Reviewed.",
      "reviewReason": "$reason",
      "mdtChairName": "Sue Carter",
      "evidenceSources": $evidenceSources
      ${ReviewAnswersFixture.completeAnswerFields(reason, answers)}
    }
  """.trimIndent()

  private fun confirm(prisoner: String, reviewId: UUID, stage: String, body: String) = webTestClient.put()
    .uri("/csra-review/prisoner/$prisoner/review/$reviewId/$stage")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(body)
    .exchange()

  private fun finalStage(reviewId: UUID) = csraAssessmentStageRepository.findByCsraReviewIdAndStage(reviewId, CsraAssessmentStage.FINAL)

  private fun assertRefusedAsIncomplete(prisoner: String, reviewId: UUID, body: String, expectedProblem: String) {
    confirm(prisoner, reviewId, "final", body)
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("ReviewAnswersIncomplete")
      .jsonPath("$.developerMessage").value<String> { assertThat(it).contains(expectedProblem) }

    // Refused means nothing was written: no rating, and no stage.
    assertThat(csraReviewRepository.findById(reviewId).orElseThrow().finalResult).isNull()
    assertThat(finalStage(reviewId)).isNull()
  }

  @ParameterizedTest
  @CsvSource(
    "Q8001QQ, SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW",
    "Q8002QQ, SHORT_TERM_HIGH_RISK_REVIEW",
    "Q8003QQ, NEW_OR_ADDITIONAL_INFORMATION",
    "Q8004QQ, RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING",
  )
  fun `a review complete for its reason can be given a final rating`(prisoner: String, reason: String) {
    val reviewId = start(prisoner)

    confirm(prisoner, reviewId, "final", finalBody(reason)).expectStatus().isOk

    val stage = finalStage(reviewId)!!
    assertThat(stage.questionSetVersion).isEqualTo(2)
    assertThat(stage.mdtReviewed).isEqualTo(if (ReviewAnswersFixture.asksMdtQuestion(reason)) true else null)
  }

  @ParameterizedTest
  @CsvSource(
    "Q8011QQ, SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW, significantlyVulnerable",
    "Q8012QQ, NEW_OR_ADDITIONAL_INFORMATION, personalityDisorder",
    "Q8013QQ, RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING, wantsToHarmOrKill",
  )
  fun `a final rating is refused while a question the reason asks is unanswered`(prisoner: String, reason: String, field: String) {
    val reviewId = start(prisoner)

    assertRefusedAsIncomplete(prisoner, reviewId, finalBody(reason, answers = mapOf(field to null)), "not answered: $field")
  }

  @ParameterizedTest
  @CsvSource(
    "Q8021QQ, SHORT_TERM_HIGH_RISK_REVIEW, offenceArson",
    "Q8022QQ, NEW_OR_ADDITIONAL_INFORMATION, frequentCellChangeRequests",
    "Q8023QQ, RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING, offenceMurderManslaughter",
    "Q8024QQ, SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW, personalityDisorder",
  )
  fun `a final rating is refused with an answer to a question the reason does not ask`(prisoner: String, reason: String, field: String) {
    val reviewId = start(prisoner)

    assertRefusedAsIncomplete(prisoner, reviewId, finalBody(reason, answers = mapOf(field to false)), "not asked by a $reason review: $field")
  }

  @Test
  fun `the scheduled long term review may answer its healthcare and other questions, but need not`() {
    // Its pages after statement and vulnerability are not designed yet (MAPA-525), so these are allowed
    // without being required. Not answering them is covered by the complete-review test above.
    val prisoner = "Q8025QQ"
    val reviewId = start(prisoner)

    confirm(
      prisoner,
      reviewId,
      "final",
      finalBody(
        "SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW",
        answers = mapOf("healthcareIncreasedRisk" to false, "otherHighRiskIndicators" to false),
      ),
    ).expectStatus().isOk
  }

  @Test
  fun `a final rating is refused with no evidence source`() {
    val prisoner = "Q8031QQ"
    val reviewId = start(prisoner)

    assertRefusedAsIncomplete(prisoner, reviewId, finalBody("SHORT_TERM_HIGH_RISK_REVIEW", evidenceSources = "[]"), "no evidence source given")
  }

  @ParameterizedTest
  @CsvSource(
    "Q8041QQ, NEW_OR_ADDITIONAL_INFORMATION, false, mdtReviewed must be true",
    "Q8042QQ, RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING, , mdtReviewed must be true",
    "Q8043QQ, SHORT_TERM_HIGH_RISK_REVIEW, true, mdtReviewed is not asked by a SHORT_TERM_HIGH_RISK_REVIEW review",
    "Q8044QQ, SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW, false, mdtReviewed is not asked by a SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW review",
  )
  fun `the multidisciplinary team answer must match the reason`(prisoner: String, reason: String, mdtReviewed: String?, expectedProblem: String) {
    val reviewId = start(prisoner)

    assertRefusedAsIncomplete(
      prisoner,
      reviewId,
      finalBody(reason, answers = mapOf("mdtReviewed" to mdtReviewed?.toBooleanStrict())),
      expectedProblem,
    )
  }

  @ParameterizedTest
  @CsvSource(
    "Q8051QQ, offenceMurderManslaughter",
    "Q8052QQ, offenceAssistingSuicide",
    "Q8053QQ, offenceSexualAssault",
  )
  fun `a yes to a mandatory high-risk offence on a new or additional information review allows only high risk general`(prisoner: String, field: String) {
    val reviewId = start(prisoner)
    val answers = mapOf(field to true, "${field}Detail" to "Evidence on file.")

    confirm(prisoner, reviewId, "final", finalBody("NEW_OR_ADDITIONAL_INFORMATION", rating = "STANDARD", answers = answers))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("MandatoryHighRiskGeneral")
    assertThat(csraReviewRepository.findById(reviewId).orElseThrow().finalResult).isNull()

    confirm(prisoner, reviewId, "final", finalBody("NEW_OR_ADDITIONAL_INFORMATION", rating = "HIGH_GENERAL", answers = answers))
      .expectStatus().isOk
    assertThat(csraReviewRepository.findById(reviewId).orElseThrow().finalResult).isEqualTo(CsraResult.HIGH_GENERAL)
  }

  @Test
  fun `a yes to any other new or additional information question leaves the rating to the reviewer, and is stored`() {
    val prisoner = "Q8061QQ"
    val reviewId = start(prisoner)

    confirm(
      prisoner,
      reviewId,
      "final",
      finalBody(
        "NEW_OR_ADDITIONAL_INFORMATION",
        rating = "STANDARD",
        answers = mapOf(
          "secureHospitalInpatient" to true,
          "secureHospitalInpatientDetail" to "Broadmoor, 2015 to 2018.",
          "personalityDisorder" to true,
          "personalityDisorderDetail" to "Diagnosed in 2019.",
        ),
      ),
    ).expectStatus().isOk

    val stage = finalStage(reviewId)!!
    assertThat(stage.secureHospitalInpatient).isTrue()
    assertThat(stage.secureHospitalInpatientDetail).isEqualTo("Broadmoor, 2015 to 2018.")
    assertThat(stage.personalityDisorder).isTrue()
    assertThat(stage.personalityDisorderDetail).isEqualTo("Diagnosed in 2019.")
  }

  @Test
  fun `a yes to a recent change in behaviour question needs its details`() {
    val prisoner = "Q8071QQ"
    val reviewId = start(prisoner)

    confirm(
      prisoner,
      reviewId,
      "final",
      finalBody("RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING", answers = mapOf("clinicalParanoidBehaviour" to true)),
    )
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("MissingAnswerDetail")
      .jsonPath("$.developerMessage").value<String> { assertThat(it).contains("clinicalParanoidBehaviour") }
  }

  @Test
  fun `an interim rating is not held to the question set`() {
    // On the multidisciplinary team No route the interim rating comes before any question page (MAPA-522).
    val prisoner = "Q8081QQ"
    val reviewId = start(prisoner)

    confirm(
      prisoner,
      reviewId,
      "interim",
      """
      {
        "rating": "HIGH_GENERAL",
        "prisonId": "LEI",
        "reviewComment": "Interim rating until the multidisciplinary team meets.",
        "reviewReason": "NEW_OR_ADDITIONAL_INFORMATION",
        "mdtChairName": "Sue Carter"
      }
      """.trimIndent(),
    ).expectStatus().isOk
  }

  @Test
  fun `a part-way save keeps every question's answer and details, whatever the reason, and the read returns them`() {
    // Saving never applies the question set: the user may be part way through, or may have just changed the
    // reason. Every question is set here, so a field missed from the save or the read fails this test.
    val prisoner = "Q8091QQ"
    val reviewId = start(prisoner)
    val questionFields = CsraReviewQuestion.entries.joinToString("") {
      """, "${it.field}": true, "${it.detail.name}": "Details for ${it.field}""""
    }

    webTestClient.put()
      .uri("/csra-review/prisoner/$prisoner/review/$reviewId/answers")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(
        """
        { "prisonId": "LEI", "version": 1, "answers": {
          "reviewReason": "NEW_OR_ADDITIONAL_INFORMATION", "mdtReviewed": true, "evidenceSources": [] $questionFields
        } }
        """.trimIndent(),
      )
      .exchange()
      .expectStatus().isOk

    val answers = webTestClient.get()
      .uri("/csra-review/prisoner/$prisoner/review/$reviewId")
      .headers(setAuthorisation(roles = writeRole))
      .exchange()
      .expectStatus().isOk
      .expectBody<CsraReviewDto>()
      .returnResult().responseBody!!
      .stages.single { it.stage == CsraAssessmentStage.FINAL }
      .answers

    assertThat(answers.mdtReviewed).isTrue()
    CsraReviewQuestion.entries.forEach {
      assertThat(it.answerIn(answers)).describedAs(it.field).isTrue()
      assertThat(it.detailIn(answers)).describedAs(it.detail.name).isEqualTo("Details for ${it.field}")
    }
  }
}
