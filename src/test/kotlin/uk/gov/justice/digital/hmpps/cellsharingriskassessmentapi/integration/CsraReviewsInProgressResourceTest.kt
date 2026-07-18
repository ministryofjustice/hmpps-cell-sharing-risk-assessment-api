package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraReviewsInProgressResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    csraReviewRepository.deleteAll()
    hmppsAuth.stubGrantToken()
    seed()
  }

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType = CsraType.CSRA_REVIEW,
    finalResult: CsraResult? = null,
    prisonId: String = "LEI",
    createdBy: String = "SCARTER",
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      finalResult = finalResult,
      finalResultDate = finalResult?.let { assessmentDate },
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = createdBy,
    ),
  )

  private fun seed() {
    review("RV01", LocalDate.parse("2026-07-03"), createdBy = "SCARTER")
    review("RV02", LocalDate.parse("2026-07-06"), createdBy = "MSTANLEY")
    // Decoys
    review("RVDONE", LocalDate.parse("2026-07-01"), finalResult = CsraResult.HIGH) // completed review
    review("ASMT", LocalDate.parse("2026-07-02"), type = CsraType.CSRA_INITIAL_REVIEW) // an assessment, not a review
    review("RVOTHER", LocalDate.parse("2026-07-06"), prisonId = "BXI") // in progress at another prison

    prisonerSearch.stubGetPrisonerNames(
      listOf(
        RollMemberStub("RV01", "Simon", "Kettleby"),
        RollMemberStub("RV02", "Gareth", "Winrow"),
      ),
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/reviews-in-progress")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/reviews-in-progress")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `lists in-progress reviews with names, excluding completed, assessments and other prisons`() {
    webTestClient.get().uri("/csra-review/prison/LEI/reviews-in-progress")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      // ordered by started date: RV01 (07-03) then RV02 (07-06)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("RV01")
      .jsonPath("$.content[0].firstName").isEqualTo("Simon")
      .jsonPath("$.content[0].lastName").isEqualTo("Kettleby")
      .jsonPath("$.content[0].startedOn").isEqualTo("2026-07-03")
      .jsonPath("$.content[0].startedBy").isEqualTo("SCARTER")
      .jsonPath("$.content[0].reviewId").isNotEmpty
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("RV02")
  }

  @Test
  fun `returns empty for an establishment with none in progress`() {
    webTestClient.get().uri("/csra-review/prison/MDI/reviews-in-progress")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(0)
      .jsonPath("$.content.length()").isEqualTo(0)
  }
}
