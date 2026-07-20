package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
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
    stubRoll(prisonId, content, prisonerNumbers.size)
  }

  /** Stub the prison roll for [prisonId] as a single page of prisoners with their names. */
  fun stubGetPrisonRollWithNames(prisonId: String, members: List<RollMemberStub>) {
    val content = members.joinToString(",") {
      """{"prisonerNumber":"${it.prisonerNumber}","firstName":"${it.firstName}","lastName":"${it.lastName}"}"""
    }
    stubRoll(prisonId, content, members.size)
  }

  private fun stubRoll(prisonId: String, content: String, size: Int) {
    stubFor(
      // Real prisoner-search 500s on a GET without a Content-Type, so match on it: a request that
      // stops sending one no longer matches this stub and the calling test fails.
      get(urlPathEqualTo("/prisoner-search/prison/$prisonId"))
        .withHeader("Content-Type", equalTo("application/json"))
        .willReturn(
          aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""{"content":[$content],"totalElements":$size,"totalPages":1,"number":0}""")
            .withStatus(200),
        ),
    )
  }

  data class RollMemberStub(val prisonerNumber: String, val firstName: String, val lastName: String)

  /** Stub the bulk names lookup (POST /prisoner-search/prisoner-numbers) with the given members. */
  fun stubGetPrisonerNames(members: List<RollMemberStub>) {
    val body = members.joinToString(",") {
      """{"prisonerNumber":"${it.prisonerNumber}","firstName":"${it.firstName}","lastName":"${it.lastName}"}"""
    }
    stubFor(
      post(urlPathEqualTo("/prisoner-search/prisoner-numbers")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("[$body]")
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
