package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraWriteSupport.Companion.ROLLOUT_OVERRIDE_ROLE
import java.util.UUID

/**
 * The rollout gate on user writes (MAPA-363), and the guard that stops a future write endpoint quietly
 * missing it.
 *
 * Every request here uses a fixed prisoner number and a random review id. That is deliberate: the gate
 * runs before the record is loaded, so a rejection needs no fixture data at all, and the "allowed" cases
 * only assert the response is *not* 403 — what happens after the gate is those endpoints' own tests.
 */
class ActivePrisonGateTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var context: ApplicationContext

  @Test
  fun `every mutating endpoint is either gated on rollout or explicitly exempt`() {
    val mutating = context.getBeansOfType(RequestMappingHandlerMapping::class.java)
      .flatMap { (_, mapping) -> mapping.handlerMethods.keys }
      .flatMap { it.getMappings() }
      .filter { mapping -> MUTATING_METHODS.any { mapping.startsWith("$it ") } }
      .toSet()

    assertThat(mutating).withFailMessage {
      "Every mutating endpoint must be classified. Either call " +
        "writeSupport.rejectIfPrisonNotActive(request.prisonId) from its service and add it to GATED, " +
        "or add it to NOT_GATED with the reason it has to work at a prison CSRA is switched off for.\n" +
        "  unclassified: ${mutating - GATED - NOT_GATED}\n" +
        "  listed but gone: ${GATED + NOT_GATED - mutating}"
    }.isEqualTo(GATED + NOT_GATED)
  }

  @Test
  fun `every gated endpoint has a behavioural case`() {
    assertThat(gatedRequests().map { it.toString() }.toSet()).isEqualTo(GATED)
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gatedRequests")
  fun `a write for a prison CSRA is not switched on for is rejected`(request: GatedRequest) {
    // No active_agency row at all — the state every prison is in before it is rolled out.
    exchange(request)
      .expectStatus().isForbidden
      .expectBody()
      .jsonPath("errorCode").isEqualTo("PrisonNotActive")
      .jsonPath("userMessage").isEqualTo("Forbidden: CSRA is not switched on for prison $PRISON")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gatedRequests")
  fun `a write for a switched-on prison passes the gate`(request: GatedRequest) {
    switchOn(PRISON)

    exchange(request).expectStatus().value { assertThat(it).isNotEqualTo(403) }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("gatedRequests")
  fun `the rollout override role bypasses the gate`(request: GatedRequest) {
    exchange(request, roles = listOf("ROLE_CSRA_REVIEW__RW", ROLLOUT_OVERRIDE_ROLE))
      .expectStatus().value { assertThat(it).isNotEqualTo(403) }
  }

  @Test
  fun `a switched-off prison is refused just as an unknown one is`() {
    // Switching off leaves the row behind with active = false, so this is a different read from "no row".
    switchOn(PRISON)
    switchOff(PRISON)

    exchange(gatedRequests().first())
      .expectStatus().isForbidden
      .expectBody().jsonPath("errorCode").isEqualTo("PrisonNotActive")
  }

  @Test
  fun `a missing prison is still a 400, so the gate has not overtaken validation`() {
    webTestClient.post()
      .uri("/csra-review/prisoner/$PRISONER/assessment")
      .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__RW")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"prisonId":""}""")
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `the gate outranks the record lookup, so an unknown id at an inactive prison is 403 not 404`() {
    // A 404 would tell a caller with no business writing at this prison whether the id exists.
    exchange(gatedRequests()[1]).expectStatus().isForbidden
  }

  private fun exchange(
    request: GatedRequest,
    roles: List<String> = listOf("ROLE_CSRA_REVIEW__RW"),
  ): WebTestClient.ResponseSpec = webTestClient.method(request.method)
    .uri(request.uri)
    .headers(setAuthorisation(roles = roles))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(request.body)
    .exchange()

  /** One gated endpoint, with a body valid enough to get past `@Valid` and reach the gate. */
  data class GatedRequest(
    val method: HttpMethod,
    val template: String,
    val uri: String,
    val body: String,
  ) {
    override fun toString() = "$method $template"
  }

  companion object {
    private const val PRISONER = "A1111AA"
    private const val PRISON = "LEI"

    private val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

    private const val START_BODY = """{"prisonId":"$PRISON"}"""

    // `version` is non-null with no default, so a body without it never binds and 400s before the gate.
    private const val ANSWERS_BODY = """{"prisonId":"$PRISON","version":0}"""
    private const val ASSESSMENT_STAGE_BODY =
      """{"rating":"STANDARD","prisonId":"$PRISON","assessmentComment":"comment"}"""
    private const val REVIEW_STAGE_BODY =
      """{"rating":"STANDARD","prisonId":"$PRISON","reviewComment":"comment",""" +
        """"reviewReason":"SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW","mdtChairName":"A Chair"}"""

    @JvmStatic
    fun gatedRequests() = listOf(
      GatedRequest(
        HttpMethod.POST,
        "/csra-review/prisoner/{prisonerNumber}/assessment",
        "/csra-review/prisoner/$PRISONER/assessment",
        START_BODY,
      ),
      GatedRequest(
        HttpMethod.PUT,
        "/csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/provisional",
        "/csra-review/prisoner/$PRISONER/assessment/${UUID.randomUUID()}/provisional",
        ASSESSMENT_STAGE_BODY,
      ),
      GatedRequest(
        HttpMethod.PUT,
        "/csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/final",
        "/csra-review/prisoner/$PRISONER/assessment/${UUID.randomUUID()}/final",
        ASSESSMENT_STAGE_BODY,
      ),
      GatedRequest(
        HttpMethod.PUT,
        "/csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/stage/{stage}/answers",
        "/csra-review/prisoner/$PRISONER/assessment/${UUID.randomUUID()}/stage/PROVISIONAL/answers",
        ANSWERS_BODY,
      ),
      GatedRequest(
        HttpMethod.POST,
        "/csra-review/prisoner/{prisonerNumber}/review",
        "/csra-review/prisoner/$PRISONER/review",
        START_BODY,
      ),
      GatedRequest(
        HttpMethod.PUT,
        "/csra-review/prisoner/{prisonerNumber}/review/{reviewId}/interim",
        "/csra-review/prisoner/$PRISONER/review/${UUID.randomUUID()}/interim",
        REVIEW_STAGE_BODY,
      ),
      GatedRequest(
        HttpMethod.PUT,
        "/csra-review/prisoner/{prisonerNumber}/review/{reviewId}/final",
        "/csra-review/prisoner/$PRISONER/review/${UUID.randomUUID()}/final",
        REVIEW_STAGE_BODY,
      ),
    )

    /** The user write journeys. A write here is refused unless the prison is switched on. */
    private val GATED = setOf(
      "POST /csra-review/prisoner/{prisonerNumber}/assessment",
      "PUT /csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/provisional",
      "PUT /csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/final",
      "PUT /csra-review/prisoner/{prisonerNumber}/assessment/{assessmentId}/stage/{stage}/answers",
      "POST /csra-review/prisoner/{prisonerNumber}/review",
      "PUT /csra-review/prisoner/{prisonerNumber}/review/{reviewId}/interim",
      "PUT /csra-review/prisoner/{prisonerNumber}/review/{reviewId}/final",
    )

    /** Deliberately ungated, each for a reason. Adding to this set is a decision, not a formality. */
    private val NOT_GATED = setOf(
      // NOMIS is the source of truth during rollout, and migration catch-up has to be able to land a
      // prisoner's history whatever state their prison is in.
      "POST /nomis-sync/migrate/{prisonerNumber}",
      "POST /nomis-sync/sync/{prisonerNumber}",
      // The rollout switch itself. Gating it on rollout state would be circular — no prison could ever
      // be switched on.
      "PUT /active-agencies/{agencyId}",
      // hmpps-sqs queue administration, nothing to do with CSRA data.
      "PUT /queue-admin/retry-dlq/{dlqName}",
      "PUT /queue-admin/retry-all-dlqs",
      "PUT /queue-admin/purge-queue/{queueName}",
    )
  }
}
