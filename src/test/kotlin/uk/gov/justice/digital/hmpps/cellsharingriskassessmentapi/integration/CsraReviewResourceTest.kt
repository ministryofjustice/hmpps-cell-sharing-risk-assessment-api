package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraQuestionDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraResponseDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraReviewDetailDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CsraReviewResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  private fun review(
    prisonerNumber: String,
    prisonId: String? = "LEI",
    type: CsraType = CsraType.REVIEW,
    finalResult: CsraResult? = CsraResult.STANDARD,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = LocalDate.parse("2016-10-31"),
      type = type,
      finalResult = finalResult,
      finalResultDate = finalResult?.let { LocalDate.parse("2016-10-31") },
      status = CsraReviewStatus.COMPLETE,
      createdAt = LocalDateTime.parse("2016-10-31T09:15:00"),
      createdBy = "NQP56Y",
    ),
  )

  private fun withNomis(
    review: CsraReviewEntity,
    calculatedLevel: CsraLevel? = CsraLevel.STANDARD,
    reviewLevel: CsraLevel? = CsraLevel.STANDARD,
    approvedLevel: CsraLevel? = null,
    committeeCode: CsraCommitteeCode? = CsraCommitteeCode.RECP,
    reviewCommitteeCode: CsraCommitteeCode? = CsraCommitteeCode.REVIEW,
    evaluationResultCode: CsraEvaluationResultCode? = CsraEvaluationResultCode.APP,
    evaluationDate: LocalDate? = LocalDate.parse("2016-11-01"),
    comment: String? = "Assessment comment from NOMIS.",
    reviewComment: String? = "Review level comment.",
    reviewCommitteeComment: String? = "Approval committee comment.",
    nextReviewDate: LocalDate? = LocalDate.parse("2017-11-01"),
    reviewDetails: List<CsraReviewDetailDto> = emptyList(),
  ) = csraReviewNomisRepository.saveAndFlush(
    CsraReviewNomisEntity(
      csraReview = review,
      status = CsraStatus.A,
      calculatedLevel = calculatedLevel,
      reviewLevel = reviewLevel,
      approvedLevel = approvedLevel,
      committeeCode = committeeCode,
      reviewCommitteeCode = reviewCommitteeCode,
      evaluationResultCode = evaluationResultCode,
      evaluationDate = evaluationDate,
      comment = comment,
      reviewComment = reviewComment,
      reviewCommitteeComment = reviewCommitteeComment,
      nextReviewDate = nextReviewDate,
      reviewDetails = reviewDetails,
    ),
  )

  private fun get(id: UUID) = webTestClient.get().uri("/csra-review/$id")
    .headers(setAuthorisation(roles = readRole))
    .exchange()

  private fun section(code: String, vararg questions: CsraQuestionDto) = CsraReviewDetailDto(code = code, description = "$code description", questions = questions.toList())

  private fun question(code: String, description: String?, vararg answers: String?) = CsraQuestionDto(
    code = code,
    description = description,
    responses = answers.mapIndexed { i, answer -> CsraResponseDto(code = "R$i", answer = answer) },
  )

  @Test
  fun `returns 404 when the review does not exist`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns the core review with no legacy block for a DPS-created review`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("D1111DD", type = CsraType.CSRA_INITIAL_REVIEW)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.id").isEqualTo(review.id.toString())
      .jsonPath("$.prisonerNumber").isEqualTo("D1111DD")
      .jsonPath("$.prisonId").isEqualTo("LEI")
      .jsonPath("$.prisonName").isEqualTo("Leeds (HMP)")
      .jsonPath("$.type").isEqualTo("CSRA_INITIAL_REVIEW")
      .jsonPath("$.finalResult").isEqualTo("STANDARD")
      .jsonPath("$.legacy").doesNotExist()
  }

  @Test
  fun `returns the full legacy block for a migrated review`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N2222NN")
    withNomis(review, reviewDetails = listOf(section("SEC1", question("Q1", "Select Risk Rating", "Standard"))))

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.level").isEqualTo("STANDARD")
      .jsonPath("$.legacy.approvalStatus").isEqualTo("APPROVED")
      .jsonPath("$.legacy.calculatedResult").isEqualTo("STANDARD")
      .jsonPath("$.legacy.assessmentComment").isEqualTo("Assessment comment from NOMIS.")
      .jsonPath("$.legacy.approvalCommitteeComment").isEqualTo("Approval committee comment.")
      .jsonPath("$.legacy.approvalComment").isEqualTo("Review level comment.")
      .jsonPath("$.legacy.approvalDate").isEqualTo("2016-11-01")
      .jsonPath("$.legacy.nextReviewDate").isEqualTo("2017-11-01")
      .jsonPath("$.legacy.questions[0].question").isEqualTo("Select Risk Rating")
      .jsonPath("$.legacy.questions[0].answer").isEqualTo("Standard")
  }

  @Test
  fun `reports the approved result from the reviewed level, not the approved level`() {
    // NOMIS's approved CSRA is REVIEW_SUP_LEVEL_TYPE. APPROVED_SUP_LEVEL_TYPE is never populated and
    // prison-api does not even map it, so reading approvedLevel here would blank the row on every record.
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N3333NN")
    withNomis(review, calculatedLevel = CsraLevel.STANDARD, reviewLevel = CsraLevel.HI, approvedLevel = null)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.approvedResult").isEqualTo("HI")
      .jsonPath("$.legacy.calculatedResult").isEqualTo("STANDARD")
  }

  @Test
  fun `flattens sections and questions into a single list in stored order`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N4444NN")
    withNomis(
      review,
      reviewDetails = listOf(
        section("SEC1", question("Q1", "First", "a"), question("Q2", "Second", "b")),
        section("SEC2", question("Q3", "Third", "c"), question("Q4", "Fourth", "d")),
      ),
    )

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions.length()").isEqualTo(4)
      .jsonPath("$.legacy.questions[0].question").isEqualTo("First")
      .jsonPath("$.legacy.questions[1].question").isEqualTo("Second")
      .jsonPath("$.legacy.questions[2].question").isEqualTo("Third")
      .jsonPath("$.legacy.questions[3].question").isEqualTo("Fourth")
  }

  @Test
  fun `puts the first answer on answer and the rest on additionalAnswers`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N5555NN")
    withNomis(review, reviewDetails = listOf(section("SEC1", question("Q1", "Concerns", "First", "Second", "Third"))))

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions[0].answer").isEqualTo("First")
      .jsonPath("$.legacy.questions[0].additionalAnswers[0]").isEqualTo("Second")
      .jsonPath("$.legacy.questions[0].additionalAnswers[1]").isEqualTo("Third")
  }

  @Test
  fun `skips null answers rather than leaving the first answer empty`() {
    // A null leading response would otherwise blank `answer` while leaving real text in additionalAnswers,
    // and a consumer that hides unanswered questions would discard the lot.
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N6666NN")
    withNomis(review, reviewDetails = listOf(section("SEC1", question("Q1", "Concerns", null, "Real answer"))))

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions[0].answer").isEqualTo("Real answer")
      .jsonPath("$.legacy.questions[0].additionalAnswers.length()").isEqualTo(0)
  }

  @Test
  fun `falls back to the question code when NOMIS supplied no question text`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N7777NN")
    withNomis(review, reviewDetails = listOf(section("SEC1", question("QCODE", null, "Yes"))))

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions[0].question").isEqualTo("QCODE")
  }

  @Test
  fun `returns an empty question list when the review has no stored detail`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N8888NN")
    withNomis(review, reviewDetails = emptyList())

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions.length()").isEqualTo(0)
  }

  @Test
  fun `returns an empty question list when review_details is null in the database`() {
    // reviewDetails is a non-null Kotlin List over a nullable JSONB column. Writes have always stored [],
    // so this should be unreachable -- proven rather than assumed, because a SQL NULL here would put a
    // null into a non-null property and fail on read.
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("N9999NN")
    withNomis(review, reviewDetails = listOf(section("SEC1", question("Q1", "First", "a"))))
    jdbcTemplate.update("UPDATE csra_review_nomis SET review_details = NULL WHERE csra_review_id = ?", review.id!!)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.questions.length()").isEqualTo(0)
  }

  @Test
  fun `returns the committee display names alongside their codes`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("C1111CC")
    withNomis(review, committeeCode = CsraCommitteeCode.RECP, reviewCommitteeCode = CsraCommitteeCode.REVIEW)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.assessmentCommittee.code").isEqualTo("RECP")
      .jsonPath("$.legacy.assessmentCommittee.name").isEqualTo("Reception")
      .jsonPath("$.legacy.approvalCommittee.code").isEqualTo("REVIEW")
      .jsonPath("$.legacy.approvalCommittee.name").isEqualTo("Review Board")
  }

  @Test
  fun `returns no committee when NOMIS recorded none`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("C2222CC")
    withNomis(review, committeeCode = null, reviewCommitteeCode = null)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.assessmentCommittee").doesNotExist()
      .jsonPath("$.legacy.approvalCommittee").doesNotExist()
  }

  @Test
  fun `falls back to the prison id when prison-register does not know the prison`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("P1111PP", prisonId = "XYZ")

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonName").isEqualTo("XYZ")
  }

  @Test
  fun `returns no prison name when the review has no prison`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("P2222PP", prisonId = null)

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonName").doesNotExist()
  }

  @Test
  fun `reports no next review date for a row migrated before it was stored, without borrowing the prisoner's current one`() {
    // csra_next_review holds one row per PRISONER. Substituting it here would stamp a 2016 review with a
    // date set by a completely different, later review.
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = review("X1111XX")
    withNomis(review, nextReviewDate = null)
    csraNextReviewRepository.saveAndFlush(
      CsraNextReviewEntity(
        prisonerNumber = "X1111XX",
        nextReviewDate = LocalDate.parse("2027-01-01"),
        setByReviewId = review.id!!,
        updatedAt = LocalDateTime.parse("2026-01-01T00:00:00"),
        updatedBy = "NQP56Y",
      ),
    )

    get(review.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.legacy.nextReviewDate").doesNotExist()

    assertThat(csraNextReviewRepository.findByPrisonerNumber("X1111XX")!!.nextReviewDate)
      .isEqualTo(LocalDate.parse("2027-01-01"))
  }
}
