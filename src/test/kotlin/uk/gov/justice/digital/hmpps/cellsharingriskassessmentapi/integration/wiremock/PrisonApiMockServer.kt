package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
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

  /**
   * A movement from prison-api. The name/date-of-birth/location values prison-api sends are deliberately
   * defaulted to obvious placeholders: the service takes those from the prisoner-search roll instead, so
   * a test that saw these values in a response would be reading the wrong source.
   */
  data class ArrivalStub(
    val offenderNo: String,
    val movementType: String,
    val movementDateTime: String,
    val movementReasonCode: String? = null,
    val firstName: String = "MOVEMENT-FIRSTNAME",
    val lastName: String = "MOVEMENT-LASTNAME",
    val dateOfBirth: String = "1900-01-01",
    val location: String = "MOVEMENT-LOCATION",
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
        ${it.movementReasonCode?.let { code -> """"movementReasonCode":"$code",""" } ?: ""}
        "location":"${it.location}"
      }
      """.trimIndent()
    }
    stubFor(
      // The window must be bounded at both ends, so match on both query params: a request that stops
      // sending toDateTime no longer matches this stub and the calling test fails.
      get(urlPathEqualTo("/api/movements/$agencyId/in"))
        .withQueryParam("fromDateTime", matching(".+"))
        .withQueryParam("toDateTime", matching(".+"))
        .withQueryParam("allMovements", equalTo("true"))
        .willReturn(
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
