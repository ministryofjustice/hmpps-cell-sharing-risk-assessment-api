package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraRatingSummaryResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType = CsraType.CSRA_INITIAL_ASSESSMENT,
    interimResult: CsraResult? = null,
    interimResultDate: LocalDate? = null,
    finalResult: CsraResult? = null,
    finalResultDate: LocalDate? = null,
    status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,
    nextReviewDate: LocalDate? = null,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = assessmentDate,
      type = type,
      status = status,
      interimResult = interimResult,
      interimResultDate = interimResultDate,
      finalResult = finalResult,
      finalResultDate = finalResultDate,
      nextReviewDate = nextReviewDate,
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  ).also { refreshCurrentRating(it.prisonerNumber) }

  private fun getSummary(prisonerNumber: String) = webTestClient.get().uri("/csra-review/prisoner/$prisonerNumber/current-rating-summary")
    .headers(setAuthorisation(roles = readRole))
    .exchange()
    .expectStatus().isOk
    .expectBody()

  private fun json(uri: String): JsonNode = objectMapper.readTree(
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java)
      .returnResult()
      .responseBody!!,
  )

  @BeforeEach
  fun stubPrisonRegister() {
    // Only the full current-rating endpoint (used for comparison) looks up prison names.
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
  }

  @Nested
  inner class SinglePrisoner {
    @Test
    fun `returns NO_RATING with No rating text when the prisoner has no CSRA`() {
      getSummary("S0000NR")
        .jsonPath("$.prisonerNumber").isEqualTo("S0000NR")
        .jsonPath("$.status").isEqualTo("NO_RATING")
        .jsonPath("$.rating").doesNotExist()
        .jsonPath("$.ratingStage").doesNotExist()
        .jsonPath("$.reviewId").doesNotExist()
        .jsonPath("$.ratingDescription").isEqualTo("No rating")
        .jsonPath("$.ratingShortDescription").isEqualTo("No rating")
        .jsonPath("$.reviewDueDate").doesNotExist()
    }

    @Test
    fun `returns IN_PROGRESS when an assessment is started but not yet rated`() {
      review(prisonerNumber = "S0001IP", assessmentDate = LocalDate.parse("2026-02-01"))

      getSummary("S0001IP")
        .jsonPath("$.status").isEqualTo("IN_PROGRESS")
        .jsonPath("$.rating").doesNotExist()
        .jsonPath("$.ratingShortDescription").isEqualTo("No rating")
    }

    @Test
    fun `returns a final High General rating with its text, date and review due date`() {
      val completed = review(
        prisonerNumber = "S0002HG",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.HIGH_GENERAL,
        finalResultDate = LocalDate.parse("2026-01-12"),
        status = CsraReviewStatus.COMPLETE,
        nextReviewDate = LocalDate.parse("2026-07-12"),
      )

      getSummary("S0002HG")
        .jsonPath("$.status").isEqualTo("COMPLETE")
        .jsonPath("$.rating").isEqualTo("HIGH_GENERAL")
        .jsonPath("$.ratingStage").isEqualTo("FINAL")
        .jsonPath("$.reviewId").isEqualTo(completed.id.toString())
        .jsonPath("$.ratingDescription").isEqualTo("High risk – general")
        .jsonPath("$.ratingShortDescription").isEqualTo("High – general")
        .jsonPath("$.reviewDueDate").isEqualTo("2026-07-12")
    }

    @Test
    fun `does not include comments, risk-to, vulnerability or in-progress detail`() {
      review(
        prisonerNumber = "S0003LT",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.STANDARD,
        finalResultDate = LocalDate.parse("2026-01-10"),
        status = CsraReviewStatus.COMPLETE,
      )

      val summary = json("/csra-review/prisoner/S0003LT/current-rating-summary")
      assertThat(summary.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
        "prisonerNumber",
        "status",
        "rating",
        "ratingStage",
        "ratingDate",
        "reviewId",
        "ratingDescription",
        "ratingShortDescription",
        "reviewDueDate",
      )
    }

    @Test
    fun `returns 401 without a token`() {
      webTestClient.get().uri("/csra-review/prisoner/A1234BC/current-rating-summary")
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.get().uri("/csra-review/prisoner/A1234BC/current-rating-summary")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .exchange()
        .expectStatus().isForbidden
    }
  }

  @Nested
  inner class MatchesCurrentRating {
    @Test
    fun `gives the same rating, status, stage and text as current-rating`() {
      review(
        prisonerNumber = "M0001FN",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.HIGH_SPECIFIC,
        finalResultDate = LocalDate.parse("2026-01-11"),
        status = CsraReviewStatus.COMPLETE,
        nextReviewDate = LocalDate.parse("2026-04-11"),
      )
      review(
        prisonerNumber = "M0002IN",
        assessmentDate = LocalDate.parse("2026-02-01"),
        type = CsraType.CSRA_REVIEW,
        interimResult = CsraResult.HIGH_GENERAL,
        interimResultDate = LocalDate.parse("2026-02-01"),
        nextReviewDate = LocalDate.parse("2026-03-01"),
      )
      review(
        prisonerNumber = "M0003PR",
        assessmentDate = LocalDate.parse("2026-02-01"),
        interimResult = CsraResult.HIGH_SPECIFIC,
        interimResultDate = LocalDate.parse("2026-02-01"),
      )
      review(
        prisonerNumber = "M0004ST",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.STANDARD,
        finalResultDate = LocalDate.parse("2026-01-10"),
        status = CsraReviewStatus.COMPLETE,
        nextReviewDate = LocalDate.parse("2027-01-10"),
      )
      review(prisonerNumber = "M0005IP", assessmentDate = LocalDate.parse("2026-02-01"))

      listOf("M0001FN", "M0002IN", "M0003PR", "M0004ST", "M0005IP", "M0006NR").forEach { prisonerNumber ->
        val full = json("/csra-review/prisoner/$prisonerNumber/current-rating")
        val summary = json("/csra-review/prisoner/$prisonerNumber/current-rating-summary")
        listOf("status", "rating", "ratingStage", "ratingDescription", "ratingShortDescription", "reviewDueDate").forEach { field ->
          assertThat(summary.path(field).asText(null))
            .describedAs("$field for $prisonerNumber")
            .isEqualTo(full.path(field).asText(null))
        }
        // current-rating reports the in-progress review's id when nothing is rated yet; the summary's
        // reviewId is only ever the review that gave the rating, so it is absent then.
        val expectedReviewId = if (full.path("status").asText() == "IN_PROGRESS") null else full.path("reviewId").asText(null)
        assertThat(summary.path("reviewId").asText(null)).describedAs("reviewId for $prisonerNumber").isEqualTo(expectedReviewId)
      }
    }
  }

  @Nested
  inner class ManyPrisoners {
    private fun postSummaries(body: String) = webTestClient.post().uri("/csra-review/prisoners/current-rating-summary")
      .headers(setAuthorisation(roles = readRole))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(body)
      .exchange()

    @Test
    fun `returns each prisoner in the order given, once each, with no CSRA as NO_RATING`() {
      review(
        prisonerNumber = "B0001HG",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.HIGH_GENERAL,
        finalResultDate = LocalDate.parse("2026-01-10"),
        status = CsraReviewStatus.COMPLETE,
      )
      review(
        prisonerNumber = "B0002ST",
        assessmentDate = LocalDate.parse("2026-01-10"),
        finalResult = CsraResult.STANDARD,
        finalResultDate = LocalDate.parse("2026-01-10"),
        status = CsraReviewStatus.COMPLETE,
      )
      review(prisonerNumber = "B0003IP", assessmentDate = LocalDate.parse("2026-02-01"))

      postSummaries("""["B0002ST", "B0009NR", "B0001HG", "B0003IP", "B0002ST"]""")
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.length()").isEqualTo(4)
        .jsonPath("$[0].prisonerNumber").isEqualTo("B0002ST")
        .jsonPath("$[0].ratingShortDescription").isEqualTo("Standard")
        .jsonPath("$[1].prisonerNumber").isEqualTo("B0009NR")
        .jsonPath("$[1].status").isEqualTo("NO_RATING")
        .jsonPath("$[2].prisonerNumber").isEqualTo("B0001HG")
        .jsonPath("$[2].ratingShortDescription").isEqualTo("High – general")
        .jsonPath("$[3].prisonerNumber").isEqualTo("B0003IP")
        .jsonPath("$[3].status").isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `returns an empty list for an empty request`() {
      postSummaries("[]")
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `rejects more than the maximum number of prisoners`() {
      val tooMany = (1..1001).joinToString(prefix = "[", postfix = "]") { "\"X${it.toString().padStart(5, '0')}XX\"" }
      postSummaries(tooMany)
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.post().uri("/csra-review/prisoners/current-rating-summary")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""["A1234BC"]""")
        .exchange()
        .expectStatus().isForbidden
    }
  }
}
