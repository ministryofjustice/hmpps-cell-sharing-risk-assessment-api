package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiApiExtension
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonApiApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.ActiveAgencyEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.ActiveAgencyRepository
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper
import java.time.LocalDateTime

@ExtendWith(HmppsAuthApiExtension::class, PrisonRegisterApiExtension::class, PrisonerSearchApiExtension::class, PrisonApiApiExtension::class)
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

  @Autowired
  private lateinit var prisonRegisterClient: PrisonRegisterClient

  @Autowired
  private lateinit var activeAgencyRepository: ActiveAgencyRepository

  // The prison-name cache is a singleton shared across tests; evict it so each test sees its own stub.
  @BeforeEach
  fun evictPrisonRegisterCache() {
    prisonRegisterClient.evictCache()
  }

  /**
   * Every test starts with no prison switched on for CSRA.
   *
   * `active_agency` rows are not rolled back between tests and the container is shared across classes, so
   * without this a class that switches a prison on would silently weaken every later test asserting that
   * a path is *not* gated. A write test switches on what it needs with [switchOn]; deliberately not done
   * here, or the ungated paths would stop proving anything.
   */
  @BeforeEach
  fun clearActiveAgencies() {
    activeAgencyRepository.deleteAll()
  }

  /** Switches CSRA on for prisons, so the gated write endpoints accept writes for them. */
  protected fun switchOn(vararg prisonIds: String) = setActive(prisonIds, active = true)

  /** Switches CSRA off, leaving the row in place — the same thing the admin endpoint does. */
  protected fun switchOff(vararg prisonIds: String) = setActive(prisonIds, active = false)

  private fun setActive(prisonIds: Array<out String>, active: Boolean) {
    prisonIds.forEach { prisonId ->
      val existing = activeAgencyRepository.findByAgencyId(prisonId)
      activeAgencyRepository.save(
        existing?.apply { this.active = active }
          ?: ActiveAgencyEntity(
            agencyId = prisonId,
            active = active,
            updatedAt = LocalDateTime.now(clock),
            updatedBy = "TEST_USER",
          ),
      )
    }
    activeAgencyRepository.flush()
  }

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
    prisonRegister.stubHealthPing(status)
    prisonerSearch.stubHealthPing(status)
    prisonApi.stubHealthPing(status)
  }
}
