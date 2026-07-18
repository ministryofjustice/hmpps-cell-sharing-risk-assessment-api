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
    // On the roll: A0001-A0005 (A0005 has an unmapped movement type). A9999 is NOT on the roll (released).
    prisonerSearch.stubGetPrisonRoll("LEI", listOf("A0001", "A0002", "A0003", "A0004", "A0005"))
    prisonApi.stubGetArrivals(
      "LEI",
      listOf(
        ArrivalStub("A0001", "Daniel", "Havers", "1972-02-03", "ADM", "2023-12-05T14:03:00", "Reception"),
        ArrivalStub("A0002", "John", "Smith", "1975-03-18", "TRN", "2023-12-04T13:01:00", "Reception"),
        ArrivalStub("A0003", "Rhys", "Calder", "1982-10-22", "CRT", "2023-12-05T12:05:00", "C-2-005"),
        // Two IN-movements for A0004 — the list should keep only the latest (the court return)
        ArrivalStub("A0004", "Theo", "King", "1990-06-06", "ADM", "2023-12-03T10:00:00", "Reception"),
        ArrivalStub("A0004", "Theo", "King", "1990-06-06", "CRT", "2023-12-05T09:00:00", "B-1-045"),
        // Unmapped movement type -> excluded
        ArrivalStub("A0005", "Owen", "King", "1985-04-01", "OTHER", "2023-12-05T11:00:00", "Reception"),
        // Not on the roll -> excluded
        ArrivalStub("A9999", "Gone", "Away", "1980-01-01", "ADM", "2023-12-05T08:00:00", "Reception"),
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
  fun `lists arrivals still in the establishment, mapped and deduped, most recent first`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?days=3")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(4)
      .jsonPath("$.fromDate").isEqualTo("2023-12-03")
      .jsonPath("$.toDate").isEqualTo("2023-12-05")
      // arrivedAt descending: A0001 14:03, A0003 12:05, A0004 09:00, A0002 (04th)
      .jsonPath("$.arrivals[0].prisonerNumber").isEqualTo("A0001")
      .jsonPath("$.arrivals[0].firstName").isEqualTo("Daniel")
      .jsonPath("$.arrivals[0].lastName").isEqualTo("Havers")
      .jsonPath("$.arrivals[0].dateOfBirth").isEqualTo("1972-02-03")
      .jsonPath("$.arrivals[0].arrivalType").isEqualTo("NEW_ADMISSION")
      .jsonPath("$.arrivals[0].arrivedAt").isEqualTo("2023-12-05T14:03:00")
      .jsonPath("$.arrivals[0].location").isEqualTo("Reception")
      .jsonPath("$.arrivals[1].prisonerNumber").isEqualTo("A0003")
      // A0004 deduped to the later court return
      .jsonPath("$.arrivals[2].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.arrivals[2].arrivalType").isEqualTo("COURT_RETURN")
      .jsonPath("$.arrivals[2].arrivedAt").isEqualTo("2023-12-05T09:00:00")
      .jsonPath("$.arrivals[3].prisonerNumber").isEqualTo("A0002")
      // counts across the whole window, all types present incl. a zero
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(1)
      .jsonPath("$.arrivalTypeCounts.TRANSFER_IN").isEqualTo(1)
      .jsonPath("$.arrivalTypeCounts.COURT_RETURN").isEqualTo(2)
      .jsonPath("$.arrivalTypeCounts.TEMPORARY_ABSENCE_RETURN").isEqualTo(0)
  }

  @Test
  fun `filters by arrival type while still reporting all counts`() {
    webTestClient.get().uri("/csra-review/prison/LEI/recent-arrivals?arrivalTypes=COURT_RETURN")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalResults").isEqualTo(2)
      .jsonPath("$.arrivals[0].prisonerNumber").isEqualTo("A0003")
      .jsonPath("$.arrivals[1].prisonerNumber").isEqualTo("A0004")
      .jsonPath("$.arrivalTypeCounts.NEW_ADMISSION").isEqualTo(1)
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
