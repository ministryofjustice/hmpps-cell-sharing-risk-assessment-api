package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Reads external movements from prison-api, the source of truth for movements in/out of establishments
 * (until a dedicated digital service replaces it). Used to build the "recent arrivals" worklist.
 *
 * Authenticated (OAuth2 client-credentials); the movements endpoint also requires the system client to
 * hold ROLE_ESTABLISHMENT_ROLL. The roll is load-bearing for the list, so failures are propagated.
 */
@Component
class PrisonApiClient(
  @param:Qualifier("prisonApiWebClient") private val webClient: WebClient,
) {
  /**
   * All IN-movements (admissions, transfers in, court/temporary-absence returns) into [prisonId] since
   * [fromDateTime]. `allMovements=true` includes prisoners currently out (e.g. at court) so an arrival in
   * the window is not missed; callers exclude anyone no longer in the establishment separately.
   */
  fun getArrivals(prisonId: String, fromDateTime: LocalDateTime): List<PrisonApiOffenderIn> = webClient
    .get()
    .uri(
      "/api/movements/{agencyId}/in?fromDateTime={fromDateTime}&allMovements=true",
      mapOf("agencyId" to prisonId, "fromDateTime" to fromDateTime.toString()),
    )
    .header("Page-Limit", PAGE_LIMIT.toString())
    .retrieve()
    .bodyToMono<List<PrisonApiOffenderIn>>()
    .block()
    .orEmpty()

  private companion object {
    // A single prison's IN-movements over a few days is small; a large page avoids paging.
    private const val PAGE_LIMIT = 10_000
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonApiOffenderIn(
  val offenderNo: String,
  val firstName: String? = null,
  val lastName: String? = null,
  val dateOfBirth: LocalDate? = null,
  /** NOMIS movement type: ADM / TRN / CRT / TAP. */
  val movementType: String,
  val movementDateTime: LocalDateTime? = null,
  /** The offender's internal location (reception or cell). */
  val location: String? = null,
)
