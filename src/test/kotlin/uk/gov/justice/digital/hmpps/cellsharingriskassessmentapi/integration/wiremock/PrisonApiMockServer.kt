package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonApiApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonApi = PrisonApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonApi.stop()
  }
}

class PrisonApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8093
  }

  data class ArrivalStub(
    val offenderNo: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String,
    val movementType: String,
    val movementDateTime: String,
    val location: String,
  )

  /** Stub GET /api/movements/{agencyId}/in with the given IN-movements. */
  fun stubGetArrivals(agencyId: String, arrivals: List<ArrivalStub>) {
    val body = arrivals.joinToString(",") {
      """
      {
        "offenderNo":"${it.offenderNo}",
        "firstName":"${it.firstName}",
        "lastName":"${it.lastName}",
        "dateOfBirth":"${it.dateOfBirth}",
        "movementType":"${it.movementType}",
        "movementTime":"${it.movementDateTime.substringAfter('T')}",
        "movementDateTime":"${it.movementDateTime}",
        "location":"${it.location}"
      }
      """.trimIndent()
    }
    stubFor(
      get(urlPathEqualTo("/api/movements/$agencyId/in")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withHeader("Total-Records", arrivals.size.toString())
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
