package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiMockServer.ArrivalStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollDetailStub

/**
 * The recent-arrivals screen (MAPA-219). The clock is fixed at 2023-12-05, so the default 3-day window
 * is the 3rd to the 5th of December.
 */
class CsraRecentArrivalsResourceTest : SqsIntegrationTestBase() {

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    // On the roll: A0001-A0008. A9999 is NOT on the roll (released). Names, dates of birth and
    // locations all come from here, not from the movement.
    prisonerSearch.stubGetPrisonRollWithDetail(
      "LEI",
      listOf(
        RollDetailStub("A0001", "Daniel", "Havers", "1972-02-03", "RECP"),
        RollDetailStub("A0002", "John", "Smith", "1975-03-18", "A-1-002"),
        RollDetailStub("A0003", "Rhys", "Calder", "1982-10-22", "C-2-005"),
        RollDetailStub("A0004", "Theo", "King", "1990-06-06", "B-1-045"),
        RollDetailStub("A0005", "Owen", "King", "1985-04-01", "D-3-011"),
        RollDetailStub("A0006", "Callum", "Reid", "1988-07-11", "A-2-020"),
        RollDetailStub("A0007", "Gareth", "Wynn", "1979-09-30", "RECP"),
        RollDetailStub("A0008", "Iain", "Hardwick", "1991-01-15", "E-1-001"),
      ),
    )
    prisonApi.stubGetArrivals(
      "LEI",
      listOf(
        // A genuinely new admission
        ArrivalStub("A0001", "ADM", "2023-12-05T14:03:00", "N"),
        // An admission that is really a transfer from another establishment
        ArrivalStub("A0002", "ADM", "2023-12-04T13:01:00", "INT"),
        // Back from court — now counts as an arrival in its own right
        ArrivalStub("A0003", "CRT", "2023-12-05T12:05:00"),
        // Admitted on the 3rd, then back from court on the 5th: two arrivals on two days
        ArrivalStub("A0004", "ADM", "2023-12-03T10:00:00", "N"),
        ArrivalStub("A0004", "CRT", "2023-12-05T09:00:00"),
        // Back from temporary absence
        ArrivalStub("A0005", "TAP", "2023-12-05T11:00:00"),
        // Transferred via court
        ArrivalStub("A0006", "ADM", "2023-12-04T08:00:00", "TRNCRT"),
        // No reason code (prison-api not yet upgraded) -> read as a new admission
        ArrivalStub("A0007", "ADM", "2023-12-04T07:00:00"),
        // A TRN recorded directly against the receiving prison
        ArrivalStub("A0008", "TRN", "2023-12-05T06:00:00"),
        // Not on the roll -> excluded
        ArrivalStub("A9999", "ADM", "2023-12-05T08:00:00", "N"),
      ),
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `groups arrivals into a section per day, most recent day first`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(9)
      .jsonPath("$.fromDate").isEqualTo("2023-12-03")
      .jsonPath("$.toDate").isEqualTo("2023-12-05")
      .jsonPath("$.days.length()").isEqualTo(3)
      .jsonPath("$.days[0].date").isEqualTo("2023-12-05")
      .jsonPath("$.days[1].date").isEqualTo("2023-12-04")
      .jsonPath("$.days[2].date").isEqualTo("2023-12-03")
      // within a day, latest arrival first
      .jsonPath("$.days[0].arrivals.length()").isEqualTo(5)
      .jsonPath("$.days[0].arrivals[0].prisonerNumber").isEqualTo("A0001")
      .jsonPath("$.days[0].arrivals[1].prisonerNumber").isEqualTo("A0003")
      .jsonPath("$.days[0].arrivals[2].prisonerNumber").isEqualTo("A0005")
      .jsonPath("$.days[0].arrivals[3].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.days[0].arrivals[4].prisonerNumber").isEqualTo("A0008")
      .jsonPath("$.days[1].arrivals.length()").isEqualTo(3)
      .jsonPath("$.days[1].arrivals[0].prisonerNumber").isEqualTo("A0002")
      .jsonPath("$.days[2].arrivals.length()").isEqualTo(1)
      .jsonPath("$.days[2].arrivals[0].prisonerNumber").isEqualTo("A0004")
  }

  @Test
  fun `takes the name, date of birth and current location from the roll rather than the movement`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.days[0].arrivals[0].prisonerNumber").isEqualTo("A0001")
      .jsonPath("$.days[0].arrivals[0].firstName").isEqualTo("Daniel")
      .jsonPath("$.days[0].arrivals[0].lastName").isEqualTo("Havers")
      .jsonPath("$.days[0].arrivals[0].dateOfBirth").isEqualTo("1972-02-03")
      .jsonPath("$.days[0].arrivals[0].arrivedAt").isEqualTo("2023-12-05T14:03:00")
      // the roll says RECP; the movement said MOVEMENT-LOCATION
      .jsonPath("$.days[0].arrivals[0].location").isEqualTo("RECP")
      // someone who arrived days ago shows where they are now, not where they arrived
      .jsonPath("$.days[2].arrivals[0].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.days[2].arrivals[0].location").isEqualTo("B-1-045")
  }

  @Test
  fun `maps every movement type to its arrival type`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // ADM with an ordinary reason
      .jsonPath("$.days[0].arrivals[0].arrivalType").isEqualTo("NEW_ADMISSION")
      // CRT
      .jsonPath("$.days[0].arrivals[1].arrivalType").isEqualTo("COURT_RETURN")
      // TAP
      .jsonPath("$.days[0].arrivals[2].arrivalType").isEqualTo("TEMPORARY_ABSENCE_RETURN")
      // TRN recorded directly
      .jsonPath("$.days[0].arrivals[4].arrivalType").isEqualTo("TRANSFER_IN")
      // ADM/INT is a transfer, not a new admission
      .jsonPath("$.days[1].arrivals[0].prisonerNumber").isEqualTo("A0002")
      .jsonPath("$.days[1].arrivals[0].arrivalType").isEqualTo("TRANSFER_IN")
      // ADM/TRNCRT likewise
      .jsonPath("$.days[1].arrivals[1].prisonerNumber").isEqualTo("A0006")
      .jsonPath("$.days[1].arrivals[1].arrivalType").isEqualTo("TRANSFER_IN")
      // ADM with no reason code at all falls back to a new admission
      .jsonPath("$.days[1].arrivals[2].prisonerNumber").isEqualTo("A0007")
      .jsonPath("$.days[1].arrivals[2].arrivalType").isEqualTo("NEW_ADMISSION")
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(3)
      .jsonPath("$.arrivalTypeCounts.TRANSFER_IN").isEqualTo(3)
      .jsonPath("$.arrivalTypeCounts.COURT_RETURN").isEqualTo(2)
      .jsonPath("$.arrivalTypeCounts.TEMPORARY_ABSENCE_RETURN").isEqualTo(1)
      .jsonPath("$.arrivalTypeCounts.length()").isEqualTo(4)
  }

  @Test
  fun `shows a prisoner who arrived on two days under each of them`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // admitted on the 3rd
      .jsonPath("$.days[2].arrivals[0].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.days[2].arrivals[0].arrivalType").isEqualTo("NEW_ADMISSION")
      .jsonPath("$.days[2].arrivals[0].arrivedAt").isEqualTo("2023-12-03T10:00:00")
      // and back from court on the 5th
      .jsonPath("$.days[0].arrivals[3].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.days[0].arrivals[3].arrivalType").isEqualTo("COURT_RETURN")
      .jsonPath("$.days[0].arrivals[3].arrivedAt").isEqualTo("2023-12-05T09:00:00")
  }

  @Test
  fun `shows only the latest arrival when a prisoner arrived twice on the same day`() {
    prisonerSearch.stubGetPrisonRollWithDetail(
      "MDI",
      listOf(RollDetailStub("B0001", "Ade", "Fell", "1981-05-05", "A-1-001")),
    )
    prisonApi.stubGetArrivals(
      "MDI",
      listOf(
        ArrivalStub("B0001", "ADM", "2023-12-05T09:00:00", "N"),
        ArrivalStub("B0001", "CRT", "2023-12-05T16:30:00"),
      ),
    )

    webTestClient.get().uri("/csra-review/prison/MDI/recent-arrivals")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(1)
      .jsonPath("$.days[0].arrivals.length()").isEqualTo(1)
      .jsonPath("$.days[0].arrivals[0].arrivedAt").isEqualTo("2023-12-05T16:30:00")
      .jsonPath("$.days[0].arrivals[0].arrivalType").isEqualTo("COURT_RETURN")
  }

  @Test
  fun `excludes a prisoner who is no longer on the roll`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.days[*].arrivals[?(@.prisonerNumber == 'A9999')]").doesNotExist()
  }

  @Test
  fun `filters by arrival type while still reporting all counts`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?arrivalTypes=COURT_RETURN")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      .jsonPath("$.days[0].arrivals.length()").isEqualTo(2)
      .jsonPath("$.days[0].arrivals[0].prisonerNumber").isEqualTo("A0003")
      .jsonPath("$.days[0].arrivals[1].prisonerNumber").isEqualTo("A0004")
      // days with no matching arrivals are still returned, empty
      .jsonPath("$.days.length()").isEqualTo(3)
      .jsonPath("$.days[1].arrivals.length()").isEqualTo(0)
      .jsonPath("$.days[2].arrivals.length()").isEqualTo(0)
      // counts ignore the filter
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(3)
      .jsonPath("$.arrivalTypeCounts.TRANSFER_IN").isEqualTo(3)
  }

  @Test
  fun `matches any of several arrival types`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?arrivalTypes=COURT_RETURN&arrivalTypes=TEMPORARY_ABSENCE_RETURN")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(3)
  }

  @Test
  fun `reflects the days window`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=1")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.fromDate").isEqualTo("2023-12-05")
      .jsonPath("$.toDate").isEqualTo("2023-12-05")
      .jsonPath("$.days.length()").isEqualTo(1)
      .jsonPath("$.days[0].date").isEqualTo("2023-12-05")
  }

  @Test
  fun `rejects a days window of less than one`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=0")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns every day as an empty section for an establishment with no arrivals`() {
    prisonerSearch.stubGetPrisonRollWithDetail("MDI", emptyList())
    prisonApi.stubGetArrivals("MDI", emptyList())

    webTestClient.get().uri("/csra-review/prison/MDI/recent-arrivals")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(0)
      .jsonPath("$.days.length()").isEqualTo(3)
      .jsonPath("$.days[0].arrivals.length()").isEqualTo(0)
      .jsonPath("$.days[1].arrivals.length()").isEqualTo(0)
      .jsonPath("$.days[2].arrivals.length()").isEqualTo(0)
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(0)
      .jsonPath("$.arrivalTypeCounts.COURT_RETURN").isEqualTo(0)
      .jsonPath("$.arrivalTypeCounts.length()").isEqualTo(4)
  }
}
