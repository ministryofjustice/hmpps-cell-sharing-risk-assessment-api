package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraHighRiskDueForReviewResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    // csra_next_review has a unique index on prisoner_number; wipe between tests as we re-seed the same
    // prisoners each test (deleting reviews cascades to csra_next_review via the FK).
    csraReviewRepository.deleteAll()
    hmppsAuth.stubGrantToken()
    seed()
  }

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType = CsraType.CSRA_INITIAL_REVIEW,
    interimResult: CsraResult? = null,
    finalResult: CsraResult? = null,
    finalResultDate: LocalDate? = null,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = assessmentDate,
      type = type,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { assessmentDate },
      finalResult = finalResult,
      finalResultDate = finalResultDate,
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  ).also { refreshCurrentRating(it.prisonerNumber) }

  private fun nextReview(review: CsraReviewEntity, prisonerNumber: String, date: LocalDate) {
    csraNextReviewRepository.saveAndFlush(
      CsraNextReviewEntity(
        prisonerNumber = prisonerNumber,
        nextReviewDate = date,
        setByReviewId = review.id!!,
        updatedAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      ),
    )
  }

  private fun seed() {
    // Included: high-general via a review -> "Last reviewed"
    nextReview(
      review("PN_HG", LocalDate.parse("2025-06-20"), type = CsraType.CSRA_REVIEW, finalResult = CsraResult.HIGH_GENERAL, finalResultDate = LocalDate.parse("2025-06-24")),
      "PN_HG",
      LocalDate.parse("2026-06-29"),
    )
    // Included: high-general interim (provisional) via an assessment -> "Last assessed"
    nextReview(
      review("PN_HGI", LocalDate.parse("2026-01-10"), interimResult = CsraResult.HIGH_GENERAL),
      "PN_HGI",
      LocalDate.parse("2026-07-14"),
    )
    // Included: high-specific via an assessment
    nextReview(
      review("PN_HS", LocalDate.parse("2025-02-15"), finalResult = CsraResult.HIGH_SPECIFIC, finalResultDate = LocalDate.parse("2025-02-20")),
      "PN_HS",
      LocalDate.parse("2026-07-25"),
    )
    // Included: legacy High via a review
    nextReview(
      review("PN_H", LocalDate.parse("2025-03-08"), type = CsraType.REVIEW, finalResult = CsraResult.HIGH, finalResultDate = LocalDate.parse("2025-03-11")),
      "PN_H",
      LocalDate.parse("2026-08-12"),
    )

    // Excluded: standard rating, even though a review is scheduled
    nextReview(review("PN_STD", LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD, finalResultDate = LocalDate.parse("2026-02-01")), "PN_STD", LocalDate.parse("2026-07-01"))
    // Excluded: high rating but no next review date
    review("PN_NOREV", LocalDate.parse("2026-02-01"), finalResult = CsraResult.HIGH_GENERAL, finalResultDate = LocalDate.parse("2026-02-01"))
    // Excluded: in-progress (no rating) with a review scheduled
    nextReview(review("PN_INPROG", LocalDate.parse("2026-02-01")), "PN_INPROG", LocalDate.parse("2026-07-05"))
    // Excluded: high + next review but NOT on the roll
    nextReview(review("PN_OFFROLL", LocalDate.parse("2026-02-01"), finalResult = CsraResult.HIGH_GENERAL, finalResultDate = LocalDate.parse("2026-02-01")), "PN_OFFROLL", LocalDate.parse("2026-07-02"))

    prisonerSearch.stubGetPrisonRollWithNames(
      "LEI",
      listOf(
        RollMemberStub("PN_HG", "Callum", "Reid"),
        RollMemberStub("PN_HGI", "Tomasz", "Ziela"),
        RollMemberStub("PN_HS", "Gareth", "Wynn"),
        RollMemberStub("PN_H", "Iain", "Hardwick"),
        RollMemberStub("PN_STD", "Rhys", "Calder"),
        RollMemberStub("PN_NOREV", "Owen", "King"),
        RollMemberStub("PN_INPROG", "Simon", "Kettleby"),
      ),
    )
  }

  private fun get(query: String = "") = webTestClient.get().uri("/csra-review/prison/LEI/high-risk-due-for-review$query")
    .headers(setAuthorisation(roles = readRole))
    .exchange()

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/high-risk-due-for-review")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/high-risk-due-for-review")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `lists only high-risk prisoners with a scheduled review, sorted by review due date`() {
    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(4)
      // due-by ascending: Reid 06-29, Ziela 07-14, Wynn 07-25, Hardwick 08-12
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN_HG")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN_HGI")
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN_HS")
      .jsonPath("$.content[3].prisonerNumber").isEqualTo("PN_H")
      // PN_HG — high general reached via a review
      .jsonPath("$.content[0].ratingType").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].provisional").isEqualTo(false)
      .jsonPath("$.content[0].reviewDueBy").isEqualTo("2026-06-29")
      .jsonPath("$.content[0].lastRatingSource").isEqualTo("REVIEW")
      .jsonPath("$.content[0].lastRatingDate").isEqualTo("2025-06-24")
      // PN_HGI — provisional high general (interim) via an assessment
      .jsonPath("$.content[1].ratingType").isEqualTo("HIGH_GENERAL_INTERIM")
      .jsonPath("$.content[1].provisional").isEqualTo(true)
      .jsonPath("$.content[1].lastRatingSource").isEqualTo("ASSESSMENT")
      .jsonPath("$.content[1].lastRatingDate").isEqualTo("2026-01-10")
      // PN_H — legacy high
      .jsonPath("$.content[3].ratingType").isEqualTo("HIGH")
      // dynamic filter types present across the establishment
      .jsonPath("$.availableRatingTypes.length()").isEqualTo(4)
      .jsonPath("$.availableRatingTypes[0]").isEqualTo("HIGH")
      .jsonPath("$.availableRatingTypes[1]").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.availableRatingTypes[2]").isEqualTo("HIGH_GENERAL_INTERIM")
      .jsonPath("$.availableRatingTypes[3]").isEqualTo("HIGH_SPECIFIC")
  }

  @Test
  fun `filters by rating type while still reporting all available types`() {
    get("?ratingTypes=HIGH_GENERAL_INTERIM&ratingTypes=HIGH_SPECIFIC")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN_HGI")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN_HS")
      .jsonPath("$.availableRatingTypes.length()").isEqualTo(4)
  }

  @Test
  fun `filters by review due date`() {
    get("?reviewDateFrom=2026-07-01&reviewDateTo=2026-07-31")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN_HGI")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN_HS")
  }

  @Test
  fun `sorts by name`() {
    get("?sort=NAME")
      .expectStatus().isOk
      .expectBody()
      // last names: Hardwick, Reid, Wynn, Ziela
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN_H")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN_HG")
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN_HS")
      .jsonPath("$.content[3].prisonerNumber").isEqualTo("PN_HGI")
  }

  @Test
  fun `returns an empty result for an establishment with none due`() {
    prisonerSearch.stubGetPrisonRollWithNames("MDI", emptyList())

    webTestClient.get().uri("/csra-review/prison/MDI/high-risk-due-for-review")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(0)
      .jsonPath("$.content.length()").isEqualTo(0)
      .jsonPath("$.availableRatingTypes.length()").isEqualTo(0)
  }
}
