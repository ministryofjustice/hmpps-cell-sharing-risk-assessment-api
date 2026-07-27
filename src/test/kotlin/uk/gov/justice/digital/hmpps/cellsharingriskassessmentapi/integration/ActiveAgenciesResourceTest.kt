package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.ActiveAgencyRepository

class ActiveAgenciesResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var activeAgencyRepository: ActiveAgencyRepository

  private val adminRole = listOf("ROLE_PRISONER_CSRA__ADMIN")

  @BeforeEach
  fun setUp() {
    activeAgencyRepository.deleteAll()
    prisonRegister.stubGetPrisons(
      prisons = mapOf("MDI" to "Moorland (HMP)", "LEI" to "Leeds (HMP)", "XXI" to "Closed (HMP)"),
      closed = setOf("XXI"),
    )
  }

  private fun setActive(agencyId: String, active: Boolean) = webTestClient.put()
    .uri("/active-agencies/$agencyId")
    .headers(setAuthorisation(roles = adminRole))
    .bodyValue(mapOf("active" to active))
    .exchange()
    .expectStatus().isOk

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/active-agencies")
      .exchange()
      .expectStatus().isUnauthorized

    webTestClient.get().uri("/active-agencies/all")
      .exchange()
      .expectStatus().isUnauthorized

    webTestClient.put().uri("/active-agencies/MDI")
      .bodyValue(mapOf("active" to true))
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    val wrongRole = listOf("ROLE_CSRA_REVIEW__R")

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = wrongRole))
      .exchange()
      .expectStatus().isForbidden

    webTestClient.get().uri("/active-agencies/all")
      .headers(setAuthorisation(roles = wrongRole))
      .exchange()
      .expectStatus().isForbidden

    webTestClient.put().uri("/active-agencies/MDI")
      .headers(setAuthorisation(roles = wrongRole))
      .bodyValue(mapOf("active" to true))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `lists the operational prisons sorted by name, with a closed one excluded`() {
    setActive("MDI", true)

    webTestClient.get().uri("/active-agencies/all")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(2)
      .jsonPath("$[0].agencyId").isEqualTo("LEI")
      .jsonPath("$[0].name").isEqualTo("Leeds (HMP)")
      .jsonPath("$[0].active").isEqualTo(false)
      .jsonPath("$[1].agencyId").isEqualTo("MDI")
      .jsonPath("$[1].active").isEqualTo(true)
  }

  @Test
  fun `a switched-on prison stays listed once it is no longer operational`() {
    setActive("XXI", true)

    webTestClient.get().uri("/active-agencies/all")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(3)
      .jsonPath("$[?(@.agencyId == 'XXI')].active").isEqualTo(true)
  }

  @Test
  fun `switching a prison on publishes it to both active-agencies and info`() {
    setActive("MDI", true)
      .expectBody()
      .jsonPath("$.agencyId").isEqualTo("MDI")
      .jsonPath("$.name").isEqualTo("Moorland (HMP)")
      .jsonPath("$.active").isEqualTo(true)

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$").isEqualTo(listOf("MDI"))

    // /info is public - the frontend reads the rollout state without a token.
    webTestClient.get().uri("/info")
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("activeAgencies").isEqualTo(listOf("MDI"))
  }

  @Test
  fun `switching a prison off removes it from both, leaving the others`() {
    setActive("MDI", true)
    setActive("LEI", true)

    setActive("MDI", false)

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$").isEqualTo(listOf("LEI"))

    webTestClient.get().uri("/info")
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("activeAgencies").isEqualTo(listOf("LEI"))
  }

  @Test
  fun `switching a prison on twice is idempotent`() {
    setActive("MDI", true)
    setActive("MDI", true)

    webTestClient.get().uri("/active-agencies")
      .headers(setAuthorisation(roles = adminRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$").isEqualTo(listOf("MDI"))
  }
}
