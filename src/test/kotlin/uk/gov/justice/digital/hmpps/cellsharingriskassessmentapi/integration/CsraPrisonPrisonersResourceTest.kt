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

class CsraPrisonPrisonersResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    seedRoll()
  }

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType = CsraType.CSRA_INITIAL_ASSESSMENT,
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

  // A fixed 7-prisoner roll: standard, no-record, high-general review, in-progress review, high-specific,
  // provisional (an assessment's Day 1 rating) and interim (a review's first-sitting rating).
  private fun seedRoll() {
    review("PN02", LocalDate.parse("2026-03-05"), finalResult = CsraResult.STANDARD, finalResultDate = LocalDate.parse("2026-03-05"))
    // PN01 has no CSRA record -> No rating
    review("PN04", LocalDate.parse("2026-03-01"), type = CsraType.CSRA_REVIEW, finalResult = CsraResult.HIGH_GENERAL, finalResultDate = LocalDate.parse("2026-03-03"))
    review("PN06", LocalDate.parse("2026-03-06"), type = CsraType.CSRA_REVIEW) // in progress -> No rating
    review("PN03", LocalDate.parse("2026-03-04"), finalResult = CsraResult.HIGH_SPECIFIC, finalResultDate = LocalDate.parse("2026-03-04"))
    review("PN05", LocalDate.parse("2026-03-02"), interimResult = CsraResult.HIGH_GENERAL) // provisional
    // Same rating and the same interimResult column as PN05, but on a review rather than an assessment —
    // the pair only differ by ratingStage.
    review("PN07", LocalDate.parse("2026-03-07"), type = CsraType.CSRA_REVIEW, interimResult = CsraResult.HIGH_GENERAL) // interim

    prisonerSearch.stubGetPrisonRollWithNames(
      "LEI",
      listOf(
        RollMemberStub("PN01", "Matthew", "Doyle"),
        RollMemberStub("PN02", "Rhys", "Calder"),
        RollMemberStub("PN03", "Gareth", "Wynn"),
        RollMemberStub("PN04", "Iain", "Hardwick"),
        RollMemberStub("PN05", "Tomasz", "Ziela"),
        RollMemberStub("PN06", "Simon", "Kettleby"),
        RollMemberStub("PN07", "Terry", "Mimms"),
      ),
    )
  }

  private fun get(query: String = "") = webTestClient.get().uri("/csra-review/prison/LEI/prisoners$query")
    .headers(setAuthorisation(roles = readRole))
    .exchange()

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/prisoners")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/prisoners")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `lists the whole roll sorted by name, mapping each current rating`() {
    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(7)
      .jsonPath("$.totalPages").isEqualTo(1)
      .jsonPath("$.page").isEqualTo(0)
      .jsonPath("$.size").isEqualTo(25)
      // sorted by last name: Calder, Doyle, Hardwick, Kettleby, Mimms, Wynn, Ziela
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN02")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN01")
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN04")
      .jsonPath("$.content[3].prisonerNumber").isEqualTo("PN06")
      .jsonPath("$.content[4].prisonerNumber").isEqualTo("PN07")
      .jsonPath("$.content[5].prisonerNumber").isEqualTo("PN03")
      .jsonPath("$.content[6].prisonerNumber").isEqualTo("PN05")
      // PN02 Calder — standard, assessment
      .jsonPath("$.content[0].rating").isEqualTo("STANDARD")
      .jsonPath("$.content[0].provisional").isEqualTo(false)
      .jsonPath("$.content[0].ratingStage").isEqualTo("FINAL")
      .jsonPath("$.content[0].assessmentType").isEqualTo("ASSESSMENT")
      .jsonPath("$.content[0].assessedOn").isEqualTo("2026-03-05")
      // PN01 Doyle — no CSRA record
      .jsonPath("$.content[1].rating").isEmpty
      .jsonPath("$.content[1].ratingStage").isEmpty
      .jsonPath("$.content[1].assessmentType").isEmpty
      .jsonPath("$.content[1].assessedOn").isEmpty
      // PN04 Hardwick — high general from a review, assessedOn = final result date
      .jsonPath("$.content[2].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[2].ratingStage").isEqualTo("FINAL")
      .jsonPath("$.content[2].assessmentType").isEqualTo("REVIEW")
      .jsonPath("$.content[2].assessedOn").isEqualTo("2026-03-03")
      // PN06 Kettleby — in-progress review -> no rating
      .jsonPath("$.content[3].rating").isEmpty
      .jsonPath("$.content[3].ratingStage").isEmpty
      // PN07 Mimms — interim high general. Same rating and provisional flag as PN05 below; only
      // ratingStage tells the two apart, which is the whole point of the field.
      .jsonPath("$.content[4].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[4].provisional").isEqualTo(true)
      .jsonPath("$.content[4].ratingStage").isEqualTo("INTERIM")
      .jsonPath("$.content[4].assessmentType").isEqualTo("REVIEW")
      .jsonPath("$.content[4].assessedOn").isEqualTo("2026-03-07")
      // PN05 Ziela — provisional high general
      .jsonPath("$.content[6].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[6].provisional").isEqualTo(true)
      .jsonPath("$.content[6].ratingStage").isEqualTo("PROVISIONAL")
      .jsonPath("$.content[6].assessmentType").isEqualTo("ASSESSMENT")
      .jsonPath("$.content[6].assessedOn").isEqualTo("2026-03-02")
  }

  @Test
  fun `filters by rating including No rating`() {
    get("?ratings=NO_RATING&ratings=HIGH_SPECIFIC")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN01")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN06")
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN03")
  }

  @Test
  fun `filters by assessment type, excluding in-progress reviews with no rating`() {
    get("?assessmentTypes=REVIEW")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)
      // name-sorted: Hardwick (PN04), Mimms (PN07). PN06's review is unrated, so it has no type.
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN04")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN07")
  }

  @Test
  fun `filters by date of assessment`() {
    get("?fromDate=2026-03-04&toDate=2026-03-05")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)
      // name-sorted: Calder (PN02), Wynn (PN03)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN02")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN03")
  }

  @Test
  fun `sorts by assessed date descending with no-rating prisoners last`() {
    get("?sort=ASSESSED_ON&direction=DESC")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN07") // 2026-03-07
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN02") // 2026-03-05
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN03") // 2026-03-04
      .jsonPath("$.content[5].prisonerNumber").isEqualTo("PN01") // no rating
      .jsonPath("$.content[6].prisonerNumber").isEqualTo("PN06") // no rating
  }

  @Test
  fun `paginates`() {
    get("?size=2&page=1")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(7)
      .jsonPath("$.totalPages").isEqualTo(4)
      .jsonPath("$.page").isEqualTo(1)
      // name-sorted page 1 (0-based): Hardwick, Kettleby
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN04")
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN06")
  }

  @Test
  fun `sorts by rating ascending, with provisional then interim above the confirmed rating`() {
    // Expected order: No rating (PN01, PN06) < Standard (PN02) < High-specific (PN03)
    // < High-general (PN04) < High-general provisional (PN05) < High-general interim (PN07).
    // Ties within the same rank are broken by prisoner number.
    get("?sort=RATING&direction=ASC")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN01") // no rating
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN06") // no rating (in-progress)
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN02") // STANDARD
      .jsonPath("$.content[3].prisonerNumber").isEqualTo("PN03") // HIGH_SPECIFIC
      .jsonPath("$.content[4].prisonerNumber").isEqualTo("PN04") // HIGH_GENERAL
      .jsonPath("$.content[5].prisonerNumber").isEqualTo("PN05") // HIGH_GENERAL (provisional)
      .jsonPath("$.content[6].prisonerNumber").isEqualTo("PN07") // HIGH_GENERAL (interim)
  }

  @Test
  fun `sorts by rating descending`() {
    // Reverse of ASC; ties within the same rank still broken by prisoner number ascending.
    get("?sort=RATING&direction=DESC")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("PN07") // HIGH_GENERAL (interim)
      .jsonPath("$.content[1].prisonerNumber").isEqualTo("PN05") // HIGH_GENERAL (provisional)
      .jsonPath("$.content[2].prisonerNumber").isEqualTo("PN04") // HIGH_GENERAL
      .jsonPath("$.content[3].prisonerNumber").isEqualTo("PN03") // HIGH_SPECIFIC
      .jsonPath("$.content[4].prisonerNumber").isEqualTo("PN02") // STANDARD
      .jsonPath("$.content[5].prisonerNumber").isEqualTo("PN01") // no rating
      .jsonPath("$.content[6].prisonerNumber").isEqualTo("PN06") // no rating (in-progress)
  }
}
