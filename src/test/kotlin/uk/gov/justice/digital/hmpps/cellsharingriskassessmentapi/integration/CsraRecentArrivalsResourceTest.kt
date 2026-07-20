package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiMockServer.ArrivalStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch

class CsraRecentArrivalsResourceTest : SqsIntegrationTestBase() {

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    // On the roll: A0001-A0008. A9999 is NOT on the roll (released).
    prisonerSearch.stubGetPrisonRoll("LEI", listOf("A0001", "A0002", "A0003", "A0004", "A0005", "A0006", "A0007", "A0008"))
    prisonApi.stubGetArrivals(
      "LEI",
      listOf(
        // A genuinely new admission
        ArrivalStub("A0001", "Daniel", "Havers", "1972-02-03", "ADM", "2023-12-05T14:03:00", "Reception", "N"),
        // An admission that is really a transfer from another establishment
        ArrivalStub("A0002", "John", "Smith", "1975-03-18", "ADM", "2023-12-04T13:01:00", "Reception", "INT"),
        // Back from court — was already here, so not an arrival
        ArrivalStub("A0003", "Rhys", "Calder", "1982-10-22", "CRT", "2023-12-05T12:05:00", "C-2-005"),
        // Admitted on the 3rd, popped out to court on the 5th: still one arrival, dated the 3rd
        ArrivalStub("A0004", "Theo", "King", "1990-06-06", "ADM", "2023-12-03T10:00:00", "Reception", "N"),
        ArrivalStub("A0004", "Theo", "King", "1990-06-06", "CRT", "2023-12-05T09:00:00", "B-1-045"),
        // Back from temporary absence — not an arrival
        ArrivalStub("A0005", "Owen", "King", "1985-04-01", "TAP", "2023-12-05T11:00:00", "Reception"),
        // Transferred via court
        ArrivalStub("A0006", "Callum", "Reid", "1988-07-11", "ADM", "2023-12-04T08:00:00", "Reception", "TRNCRT"),
        // No reason code (prison-api not yet upgraded) -> read as a new admission
        ArrivalStub("A0007", "Gareth", "Wynn", "1979-09-30", "ADM", "2023-12-04T07:00:00", "Reception"),
        // TRN is only ever the out-leg of a transfer, never an arrival
        ArrivalStub("A0008", "Iain", "Hardwick", "1991-01-15", "TRN", "2023-12-05T06:00:00", "Reception"),
        // Not on the roll -> excluded
        ArrivalStub("A9999", "Gone", "Away", "1980-01-01", "ADM", "2023-12-05T08:00:00", "Reception", "N"),
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
  fun `lists only genuine admissions still in the establishment, most recent first`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(5)
      .jsonPath("$.fromDate").isEqualTo("2023-12-03")
      .jsonPath("$.toDate").isEqualTo("2023-12-05")
      // arrivedAt descending
      .jsonPath("$.arrivals[0].prisonerNumber").isEqualTo("A0001")
      .jsonPath("$.arrivals[0].firstName").isEqualTo("Daniel")
      .jsonPath("$.arrivals[0].lastName").isEqualTo("Havers")
      .jsonPath("$.arrivals[0].dateOfBirth").isEqualTo("1972-02-03")
      .jsonPath("$.arrivals[0].arrivalType").isEqualTo("NEW_ADMISSION")
      .jsonPath("$.arrivals[0].arrivedAt").isEqualTo("2023-12-05T14:03:00")
      .jsonPath("$.arrivals[0].location").isEqualTo("Reception")
      .jsonPath("$.arrivals[1].prisonerNumber").isEqualTo("A0002")
      .jsonPath("$.arrivals[1].arrivalType").isEqualTo("TRANSFER_IN")
      .jsonPath("$.arrivals[2].prisonerNumber").isEqualTo("A0006")
      .jsonPath("$.arrivals[2].arrivalType").isEqualTo("TRANSFER_IN")
      .jsonPath("$.arrivals[3].prisonerNumber").isEqualTo("A0007")
      .jsonPath("$.arrivals[3].arrivalType").isEqualTo("NEW_ADMISSION")
      // A0004's court return neither re-dates nor re-labels their admission
      .jsonPath("$.arrivals[4].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.arrivals[4].arrivalType").isEqualTo("NEW_ADMISSION")
      .jsonPath("$.arrivals[4].arrivedAt").isEqualTo("2023-12-03T10:00:00")
      // counts across the whole window
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(3)
      .jsonPath("$.arrivalTypeCounts.TRANSFER_IN").isEqualTo(2)
      .jsonPath("$.arrivalTypeCounts.length()").isEqualTo(2)
  }

  @Test
  fun `excludes prisoners whose only movement was a court or temporary absence return`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.arrivals[?(@.prisonerNumber == 'A0003')]").doesNotExist()
      .jsonPath("$.arrivals[?(@.prisonerNumber == 'A0005')]").doesNotExist()
      .jsonPath("$.arrivals[?(@.prisonerNumber == 'A0008')]").doesNotExist()
      .jsonPath("$.arrivals[?(@.prisonerNumber == 'A9999')]").doesNotExist()
  }

  @Test
  fun `filters by arrival type while still reporting all counts`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?arrivalTypes=TRANSFER_IN")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      .jsonPath("$.arrivals[0].prisonerNumber").isEqualTo("A0002")
      .jsonPath("$.arrivals[1].prisonerNumber").isEqualTo("A0006")
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(3)
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
  }

  @Test
  fun `returns empty for an establishment with no arrivals`() {
    prisonerSearch.stubGetPrisonRoll("MDI", emptyList())
    prisonApi.stubGetArrivals("MDI", emptyList())

    webTestClient.get().uri("/csra-review/prison/MDI/recent-arrivals")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(0)
      .jsonPath("$.arrivals.length()").isEqualTo(0)
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(0)
  }
}
