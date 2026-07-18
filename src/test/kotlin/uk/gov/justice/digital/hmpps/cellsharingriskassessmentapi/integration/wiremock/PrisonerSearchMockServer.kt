package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonerSearchApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonerSearch = PrisonerSearchMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonerSearch.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonerSearch.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonerSearch.stop()
  }
}

class PrisonerSearchMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8092
  }

  /** Stub the prison roll for [prisonId] as a single page of the given prisoner numbers. */
  fun stubGetPrisonRoll(prisonId: String, prisonerNumbers: List<String>) {
    val content = prisonerNumbers.joinToString(",") { """{"prisonerNumber":"$it"}""" }
    stubFor(
      get(urlPathEqualTo("/prisoner-search/prison/$prisonId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """{"content":[$content],"totalElements":${prisonerNumbers.size},"totalPages":1,"number":0}""",
          )
          .withStatus(200),
      ),
    )
  }

  fun stubHealthPing(status: Int) {
    stubFor(
      get("/health/ping").willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(if (status == 200) """{"status":"UP"}""" else """{"status":"DOWN"}""")
          .withStatus(status),
      ),
    )
  }
}
