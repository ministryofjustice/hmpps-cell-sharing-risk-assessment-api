package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraPrisonRatingSummaryResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    // The prisoner-search roll call is authenticated (client-credentials).
    hmppsAuth.stubGrantToken()
  }

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    interimResult: CsraResult? = null,
    finalResult: CsraResult? = null,
    prisonId: String? = "LEI",
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = CsraType.CSRA_INITIAL_REVIEW,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { assessmentDate },
      finalResult = finalResult,
      finalResultDate = finalResult?.let { assessmentDate },
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  ).also { refreshCurrentRating(it.prisonerNumber) }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/rating-summary")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/rating-summary")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `counts each roll member by their current rating, treating no record and in-progress as No rating`() {
    review("RS0001AA", LocalDate.parse("2026-03-01"), finalResult = CsraResult.HIGH)
    review("RS0002AA", LocalDate.parse("2026-03-02"), interimResult = CsraResult.HIGH_GENERAL) // provisional high
    review("RS0003AA", LocalDate.parse("2026-03-03"), finalResult = CsraResult.HIGH_SPECIFIC)
    review("RS0004AA", LocalDate.parse("2026-03-04"), finalResult = CsraResult.STANDARD)
    review("RS0005AA", LocalDate.parse("2026-03-05")) // started, no rating -> in progress
    // RS0006AA is on the roll but has no CSRA record at all

    prisonerSearch.stubGetPrisonRoll(
      "LEI",
      listOf("RS0001AA", "RS0002AA", "RS0003AA", "RS0004AA", "RS0005AA", "RS0006AA"),
    )

    webTestClient.get().uri("/csra-review/prison/LEI/rating-summary")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonId").isEqualTo("LEI")
      .jsonPath("$.total").isEqualTo(6)
      .jsonPath("$.highRisk").isEqualTo(3)
      .jsonPath("$.standardRisk").isEqualTo(1)
      .jsonPath("$.noRating").isEqualTo(2)
  }

  @Test
  fun `uses the latest review per prisoner and ignores prisoners not on the roll`() {
    // On the roll: an earlier HIGH superseded by a later STANDARD -> counts as standard.
    review("RS0100AA", LocalDate.parse("2024-01-01"), finalResult = CsraResult.HIGH, prisonId = "MDI")
    review("RS0100AA", LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD, prisonId = "LEI")
    // Has a HIGH rating but is NOT on the roll -> must not be counted.
    review("RS0101AA", LocalDate.parse("2026-02-01"), finalResult = CsraResult.HIGH)

    prisonerSearch.stubGetPrisonRoll("LEI", listOf("RS0100AA"))

    webTestClient.get().uri("/csra-review/prison/LEI/rating-summary")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.total").isEqualTo(1)
      .jsonPath("$.highRisk").isEqualTo(0)
      .jsonPath("$.standardRisk").isEqualTo(1)
      .jsonPath("$.noRating").isEqualTo(0)
  }

  @Test
  fun `returns zero counts for an empty roll`() {
    prisonerSearch.stubGetPrisonRoll("MDI", emptyList())

    webTestClient.get().uri("/csra-review/prison/MDI/rating-summary")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.total").isEqualTo(0)
      .jsonPath("$.highRisk").isEqualTo(0)
      .jsonPath("$.standardRisk").isEqualTo(0)
      .jsonPath("$.noRating").isEqualTo(0)
  }
}
