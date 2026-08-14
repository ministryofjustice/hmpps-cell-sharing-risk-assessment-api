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
    startedAt: LocalDateTime,
    type: CsraType = CsraType.CSRA_REVIEW,
    finalResult: CsraResult? = null,
    prisonId: String = "LEI",
    createdBy: String = "SCARTER",
    // Deliberately a month after the start date: the screen reports when the review was *started*, so a
    // response that read assessmentDate instead would fail rather than coincidentally pass.
    assessmentDate: LocalDate = startedAt.toLocalDate().plusMonths(1),
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      finalResult = finalResult,
      finalResultDate = finalResult?.let { assessmentDate },
      createdAt = startedAt,
      createdBy = createdBy,
    ),
  )

  private fun seed() {
    review("RV01", LocalDateTime.parse("2026-07-03T09:15:00"), createdBy = "SCARTER")
    review("RV02", LocalDateTime.parse("2026-07-06T14:40:00"), createdBy = "MSTANLEY")
    // Decoys
    review("RVDONE", LocalDateTime.parse("2026-07-01T09:00:00"), finalResult = CsraResult.HIGH) // completed review
    review("ASMT", LocalDateTime.parse("2026-07-02T09:00:00"), type = CsraType.CSRA_INITIAL_REVIEW) // an assessment
    review("RVOTHER", LocalDateTime.parse("2026-07-06T09:00:00"), prisonId = "BXI") // in progress at another prison
    review("RVOUT", LocalDateTime.parse("2026-07-04T09:00:00")) // started here, since released
    review("RVGONE", LocalDateTime.parse("2026-07-05T09:00:00")) // started here, unknown to prisoner-search

    prisonerSearch.stubGetPrisonerNames(
      listOf(
        RollMemberStub("RV01", "Simon", "Kettleby", prisonId = "LEI"),
        RollMemberStub("RV02", "Gareth", "Winrow", prisonId = "LEI"),
        // Released: prisoner-search reports the released as OUT.
        RollMemberStub("RVOUT", "Mubashir", "Khan", prisonId = "OUT"),
        // RVGONE is absent from the response entirely.
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
      .jsonPath("$.content[0].startedBy").isEqualTo("SCARTER")
      .jsonPath("$.content[0].reviewId").isNotEmpty
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("RV02")
  }

  @Test
  fun `reports the date the review was started, not the assessment date`() {
    webTestClient.get().uri("/csra-review/prison/LEI/reviews-in-progress")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].startedOn").isEqualTo("2026-07-03")
      .jsonPath("$.content[1].startedOn").isEqualTo("2026-07-06")
  }

  @Test
  fun `excludes prisoners who have left the establishment`() {
    // The tidy-up that closes in-progress work only runs on the next admission, so a released prisoner's
    // review is still IN_PROGRESS against this prison. It must not reach the worklist.
    webTestClient.get().uri("/csra-review/prison/LEI/reviews-in-progress")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[?(@.prisonerNumber == 'RVOUT')]").doesNotExist()
      .jsonPath("$.content[?(@.prisonerNumber == 'RVGONE')]").doesNotExist()
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
