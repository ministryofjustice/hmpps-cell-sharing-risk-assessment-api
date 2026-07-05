package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class PrisonRegisterClientTest {

  private val server = WireMockServer(0)
  private lateinit var client: PrisonRegisterClient

  @BeforeEach
  fun setUp() {
    server.start()
    client = PrisonRegisterClient(WebClient.builder().baseUrl(server.baseUrl()).build())
  }

  @AfterEach
  fun tearDown() = server.stop()

  private fun stubPrisons(body: String, status: Int = 200) {
    server.stubFor(
      get(urlEqualTo("/prisons")).willReturn(
        aResponse().withHeader("Content-Type", "application/json").withBody(body).withStatus(status),
      ),
    )
  }

  @Test
  fun `maps the prison list to id-to-name pairs, ignoring unknown fields`() {
    stubPrisons(
      """[
        {"prisonId":"LEI","prisonName":"Leeds (HMP)","active":true},
        {"prisonId":"MDI","prisonName":"Moorland (HMP)","active":false}
      ]""",
    )

    assertThat(client.getPrisonNames())
      .containsExactlyInAnyOrderEntriesOf(mapOf("LEI" to "Leeds (HMP)", "MDI" to "Moorland (HMP)"))
  }

  @Test
  fun `caches the result so it only calls prison-register once`() {
    stubPrisons("""[{"prisonId":"LEI","prisonName":"Leeds (HMP)"}]""")

    client.getPrisonNames()
    client.getPrisonNames()

    server.verify(1, getRequestedFor(urlEqualTo("/prisons")))
  }

  @Test
  fun `returns an empty map when prison-register errors`() {
    stubPrisons("", status = 500)

    assertThat(client.getPrisonNames()).isEmpty()
  }
}
