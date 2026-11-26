package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Saving a review part way through and reading it back (MAPA-537). */
class CsraReviewAnswersResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    switchOn("LEI", "WWI")
  }

  private fun answers(
    reason: String = "NEW_OR_ADDITIONAL_INFORMATION",
    evidenceSources: String = """[{"source":"OASYS"}]""",
    extra: String = "",
  ) = """{ "reviewReason": "$reason", "evidenceSources": $evidenceSources $extra }"""

  private fun start(prisoner: String, answers: String? = answers(), prisonId: String = "LEI") = webTestClient.post()
    .uri("/csra-review/prisoner/$prisoner/review")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "$prisonId" ${answers?.let { """, "answers": $it""" } ?: ""} }""")
    .exchange()

  private fun startOk(prisoner: String, answers: String? = answers()) = start(prisoner, answers)
    .expectStatus().isCreated
    .expectBody<CsraReviewStarted>()
    .returnResult().responseBody!!
    .reviewId

  private fun save(prisoner: String, reviewId: UUID, version: Int, answers: String = answers(), prisonId: String = "LEI") = webTestClient.put()
    .uri("/csra-review/prisoner/$prisoner/review/$reviewId/answers")
    .headers(setAuthorisation(roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue("""{ "prisonId": "$prisonId", "version": $version, "answers": $answers }""")
    .exchange()

  private fun get(prisoner: String, reviewId: UUID, roles: List<String> = writeRole) = webTestClient.get()
    .uri("/csra-review/prisoner/$prisoner/review/$reviewId")
    .headers(setAuthorisation(roles = roles))
    .exchange()

  private fun getOk(prisoner: String, reviewId: UUID) = get(prisoner, reviewId)
    .expectStatus().isOk
    .expectBody<CsraReviewDto>()
    .returnResult().responseBody!!

  private fun finalStage(review: CsraReviewDto) = review.stages.single { it.stage == CsraAssessmentStage.FINAL }

  private fun confirm(prisoner: String, reviewId: UUID, stage: String, reason: String = "NEW_OR_ADDITIONAL_INFORMATION", rating: String = "STANDARD", nextReviewDate: String? = null) = webTestClient.put()
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
        ${nextReviewDate?.let { """, "nextReviewDate": "$it"""" } ?: ""}
      }
      """.trimIndent(),
    )
    .exchange()

  private fun seed(prisoner: String, type: CsraType = CsraType.CSRA_REVIEW, status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisoner,
      prisonId = "LEI",
      assessmentDate = LocalDate.parse("2023-12-01"),
      type = type,
      status = status,
      createdAt = LocalDateTime.parse("2023-12-01T09:00:00"),
      createdBy = "SCARTER",
    ),
  ).id!!

  @Test
  fun `starting with answers saves them on the final stage without a rating`() {
    val prisoner = "W8001WW"

    val reviewId = startOk(
      prisoner,
      answers(evidenceSources = """[{"source":"OTHER","details":"Wing intelligence report"},{"source":"DPS"}]"""),
    )

    val review = getOk(prisoner, reviewId)
    assertThat(review.status).isEqualTo(CsraReviewStatus.IN_PROGRESS)
    assertThat(review.reviewReason).isEqualTo(CsraReviewReason.NEW_OR_ADDITIONAL_INFORMATION)
    assertThat(review.interimResult).isNull()
    assertThat(review.finalResult).isNull()
    val stage = finalStage(review)
    // Returned in checkbox order, whatever order they were sent in.
    assertThat(stage.answers.evidenceSources.map { it.source })
      .containsExactly(CsraReviewEvidenceSource.DPS, CsraReviewEvidenceSource.OTHER)
    assertThat(stage.answers.evidenceSources.single { it.source == CsraReviewEvidenceSource.OTHER }.details)
      .isEqualTo("Wing intelligence report")
    assertThat(stage.lastSavedBy).isEqualTo(SYSTEM_USERNAME)
    assertThat(stage.lastSavedAt).isNotNull()
    assertThat(stage.completedBy).isNull()
    assertThat(stage.completedAt).isNull()
    assertThat(stage.version).isEqualTo(1)
    // A save is not a rating.
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber(prisoner)?.rating).isNull()
  }

  @Test
  fun `starting without answers saves no stage`() {
    val prisoner = "W8002WW"

    val reviewId = startOk(prisoner, answers = null)

    val review = getOk(prisoner, reviewId)
    assertThat(review.stages).isEmpty()
    assertThat(review.reviewReason).isNull()
  }

  @Test
  fun `a start whose answers have no reason is rejected and starts nothing`() {
    val prisoner = "W8003WW"

    start(prisoner, answers = """{ "evidenceSources": [{"source":"OASYS"}] }""")
      .expectStatus().isBadRequest

    assertThat(csraReviewRepository.findAll().filter { it.prisonerNumber == prisoner }).isEmpty()
  }

  @Test
  fun `a start with the same evidence source twice is rejected and starts nothing`() {
    val prisoner = "W8004WW"

    start(prisoner, answers(evidenceSources = """[{"source":"OASYS"},{"source":"OASYS"}]"""))
      .expectStatus().isBadRequest

    assertThat(csraReviewRepository.findAll().filter { it.prisonerNumber == prisoner }).isEmpty()
  }

  @Test
  fun `each save replaces the answers, so a cleared answer comes back empty`() {
    val prisoner = "W8005WW"
    val reviewId = startOk(prisoner)

    save(
      prisoner,
      reviewId,
      version = 1,
      answers(
        evidenceSources = """[{"source":"OASYS"},{"source":"OTHER","details":"Wing report"}]""",
        extra = """, "offenceMurderManslaughter": true, "offenceMurderManslaughterDetail": "Assault on cellmate" """,
      ),
    ).expectStatus().isOk
    val afterFirst = finalStage(getOk(prisoner, reviewId))
    assertThat(afterFirst.answers.offenceMurderManslaughter).isTrue()
    assertThat(afterFirst.answers.offenceMurderManslaughterDetail).isEqualTo("Assault on cellmate")
    assertThat(afterFirst.answers.evidenceSources.map { it.source })
      .containsExactly(CsraReviewEvidenceSource.OASYS, CsraReviewEvidenceSource.OTHER)

    // OASYS kept, OTHER's name changed, OTHER then dropped in favour of DPS, the murder answer cleared.
    save(prisoner, reviewId, version = afterFirst.version, answers(evidenceSources = """[{"source":"OASYS"},{"source":"DPS"}]"""))
      .expectStatus().isOk

    val afterSecond = finalStage(getOk(prisoner, reviewId))
    assertThat(afterSecond.answers.offenceMurderManslaughter).isNull()
    assertThat(afterSecond.answers.offenceMurderManslaughterDetail).isNull()
    assertThat(afterSecond.answers.evidenceSources.map { it.source })
      .containsExactly(CsraReviewEvidenceSource.DPS, CsraReviewEvidenceSource.OASYS)
    assertThat(afterSecond.version).isGreaterThan(afterFirst.version)
  }

  @Test
  fun `the save returns the review as the read does`() {
    val prisoner = "W8006WW"
    val reviewId = startOk(prisoner)

    val saved = save(prisoner, reviewId, version = 1, answers(extra = """, "otherHighRiskIndicators": false """))
      .expectStatus().isOk
      .expectBody<CsraReviewDto>()
      .returnResult().responseBody!!

    assertThat(saved).isEqualTo(getOk(prisoner, reviewId))
    assertThat(finalStage(saved).answers.otherHighRiskIndicators).isFalse()
  }

  @Test
  fun `the first save on a review started without answers creates its final stage`() {
    val prisoner = "W8007WW"
    val reviewId = startOk(prisoner, answers = null)

    save(prisoner, reviewId, version = 1).expectStatus().isOk

    assertThat(finalStage(getOk(prisoner, reviewId)).answers.evidenceSources.map { it.source })
      .containsExactly(CsraReviewEvidenceSource.OASYS)
  }

  @Test
  fun `a save based on an out-of-date version is rejected and changes nothing`() {
    val prisoner = "W8008WW"
    val reviewId = startOk(prisoner)
    // The test clock stands still, so lastSavedAt cannot move the version here as it does in real use. Answering
    // a question does, which is what a real save after the first one does anyway.
    save(
      prisoner,
      reviewId,
      version = 1,
      answers(evidenceSources = """[{"source":"DPS"}]""", extra = """, "offenceArson": false """),
    ).expectStatus().isOk

    // Another tab, still holding version 1.
    save(prisoner, reviewId, version = 1, answers(evidenceSources = """[{"source":"PNC"}]"""))
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("StaleAnswersVersion")

    assertThat(finalStage(getOk(prisoner, reviewId)).answers.evidenceSources.map { it.source })
      .containsExactly(CsraReviewEvidenceSource.DPS)
  }

  @Test
  fun `a save with the same evidence source twice is rejected`() {
    val prisoner = "W8009WW"
    val reviewId = startOk(prisoner)

    save(prisoner, reviewId, version = 1, answers(evidenceSources = """[{"source":"PNC"},{"source":"PNC"}]"""))
      .expectStatus().isBadRequest
  }

  @Test
  fun `confirming the final rating after part-way saves keeps the saved evidence sources and marks the stage confirmed`() {
    // Regression: confirm used to clear and re-add the sources, which breaks the (stage, source) unique index
    // once a save has already stored the same source on the stage.
    val prisoner = "W8010WW"
    val reviewId = startOk(prisoner)
    save(prisoner, reviewId, version = 1).expectStatus().isOk

    confirm(prisoner, reviewId, "final").expectStatus().isOk

    val review = getOk(prisoner, reviewId)
    assertThat(review.status).isEqualTo(CsraReviewStatus.COMPLETE)
    val stage = finalStage(review)
    assertThat(stage.answers.evidenceSources.map { it.source }).containsExactly(CsraReviewEvidenceSource.OASYS)
    assertThat(stage.completedBy).isEqualTo(SYSTEM_USERNAME)
    assertThat(stage.reviewComment).isEqualTo("Reviewed.")
    assertThat(stage.mdtChairName).isEqualTo("Sue Carter")
    // The part-way save is still on record alongside the confirmation.
    assertThat(stage.lastSavedBy).isEqualTo(SYSTEM_USERNAME)
  }

  @Test
  fun `a review with a final rating can no longer be saved part way`() {
    val prisoner = "W8011WW"
    val reviewId = startOk(prisoner)
    confirm(prisoner, reviewId, "final").expectStatus().isOk
    val version = finalStage(getOk(prisoner, reviewId)).version

    save(prisoner, reviewId, version = version).expectStatus().isBadRequest
  }

  @Test
  fun `the reason can change before an interim rating but not after`() {
    val prisoner = "W8012WW"
    val reviewId = startOk(prisoner, answers = null)

    // The user went back and chose another reason before anything was rated.
    save(prisoner, reviewId, version = 1, answers(reason = "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING")).expectStatus().isOk
    val version = finalStage(getOk(prisoner, reviewId)).version
    save(prisoner, reviewId, version = version, answers(reason = "NEW_OR_ADDITIONAL_INFORMATION")).expectStatus().isOk

    confirm(prisoner, reviewId, "interim", reason = "NEW_OR_ADDITIONAL_INFORMATION", rating = "HIGH_GENERAL")
      .expectStatus().isOk
    val afterInterim = finalStage(getOk(prisoner, reviewId)).version

    save(prisoner, reviewId, version = afterInterim, answers(reason = "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING"))
      .expectStatus().isBadRequest
    confirm(prisoner, reviewId, "final", reason = "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING")
      .expectStatus().isBadRequest
    save(prisoner, reviewId, version = afterInterim, answers(reason = "NEW_OR_ADDITIONAL_INFORMATION"))
      .expectStatus().isOk
  }

  @Test
  fun `saving to an archived review is refused and reading it is a 404`() {
    val prisoner = "W8013WW"
    val reviewId = seed(prisoner, status = CsraReviewStatus.ARCHIVED)

    save(prisoner, reviewId, version = 1)
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CsraReviewNotWritable")
    get(prisoner, reviewId).expectStatus().isNotFound
  }

  @Test
  fun `saving to a review closed by a movement is refused but it can still be read`() {
    val prisoner = "W8014WW"
    val reviewId = seed(prisoner, status = CsraReviewStatus.CLOSED)

    save(prisoner, reviewId, version = 1).expectStatus().isEqualTo(409)
    assertThat(getOk(prisoner, reviewId).status).isEqualTo(CsraReviewStatus.CLOSED)
  }

  @Test
  fun `another prisoner's review, an assessment and an unknown id are all 404`() {
    val reviewId = startOk("W8015WW")
    val assessmentId = seed("W8016WW", type = CsraType.CSRA_INITIAL_ASSESSMENT)

    get("W8017WW", reviewId).expectStatus().isNotFound
    save("W8017WW", reviewId, version = 1).expectStatus().isNotFound
    get("W8016WW", assessmentId).expectStatus().isNotFound
    save("W8016WW", assessmentId, version = 1).expectStatus().isNotFound
    get("W8015WW", UUID.randomUUID()).expectStatus().isNotFound
  }

  @Test
  fun `the read role can read a review but not save one`() {
    val prisoner = "W8018WW"
    val reviewId = startOk(prisoner)

    get(prisoner, reviewId, roles = listOf("ROLE_CSRA_REVIEW__R")).expectStatus().isOk
    webTestClient.put()
      .uri("/csra-review/prisoner/$prisoner/review/$reviewId/answers")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{ "prisonId": "LEI", "version": 1, "answers": ${answers()} }""")
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `a review saved part way shows as in progress and blocks starting another`() {
    val prisoner = "W8019WW"
    startOk(prisoner)

    start(prisoner).expectStatus().isEqualTo(409)
  }

  @Test
  fun `the read shows each stage, interim then final, with the reason from the final stage`() {
    val prisoner = "W8020WW"
    val reviewId = startOk(prisoner, answers = null)
    confirm(prisoner, reviewId, "interim", rating = "HIGH_GENERAL").expectStatus().isOk
    save(prisoner, reviewId, version = 1).expectStatus().isOk

    val review = getOk(prisoner, reviewId)

    assertThat(review.stages.map { it.stage }).containsExactly(CsraAssessmentStage.INTERIM, CsraAssessmentStage.FINAL)
    assertThat(review.stages.first().completedBy).isEqualTo(SYSTEM_USERNAME)
    assertThat(review.stages.last().completedBy).isNull()
    assertThat(review.reviewReason).isEqualTo(CsraReviewReason.NEW_OR_ADDITIONAL_INFORMATION)
  }
}
