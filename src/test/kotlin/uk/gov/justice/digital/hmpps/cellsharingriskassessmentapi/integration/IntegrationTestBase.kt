package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper

@ExtendWith(HmppsAuthApiExtension::class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@AutoConfigureJson
abstract class IntegrationTestBase : TestBase() {

  @Autowired
  protected lateinit var objectMapper: ObjectMapper

  @Autowired
  protected lateinit var webTestClient: WebTestClient

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  init {
    // Resolves an issue where Wiremock keeps previous sockets open from other tests causing connection resets
    System.setProperty("http.keepAlive", "false")
  }

  protected fun setAuthorisation(
    user: String? = SYSTEM_USERNAME,
    roles: List<String> = listOf(),
    scopes: List<String> = listOf(),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(
    clientId = "hmpps-cell-sharing-risk-assessment-api",
    username = user,
    roles = roles,
    scope = scopes,
  )
  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
  }
}
