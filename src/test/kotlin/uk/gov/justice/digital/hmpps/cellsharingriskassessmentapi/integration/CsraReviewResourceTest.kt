package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Test
import java.util.UUID

class CsraReviewResourceTest : SqsIntegrationTestBase() {

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @Test
  fun `returns 404 when the review does not exist`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/${UUID.randomUUID()}")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }
}
