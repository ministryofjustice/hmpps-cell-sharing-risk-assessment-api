package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Reads the current prison roll (the prisoner numbers of everyone currently in a prison) from
 * prisoner-search-api. Used to size prison-scoped CSRA views over the whole population, including
 * prisoners who have no CSRA record ("No rating").
 *
 * This call is authenticated (OAuth2 client-credentials, see WebClientConfiguration.prisonerSearchWebClient).
 * Unlike prison-register name lookups, the roll is load-bearing for the counts, so a failure is
 * propagated rather than degraded — a partial roll would silently corrupt the totals.
 */
@Component
class PrisonerSearchClient(
  @param:Qualifier("prisonerSearchWebClient") private val webClient: WebClient,
) {
  /** The prisoner numbers of everyone currently in [prisonId]. */
  fun getPrisonRoll(prisonId: String): List<String> = getPrisonRollMembers(prisonId).map { it.prisonerNumber }

  /** Everyone currently in [prisonId], with their names (for prison-scoped prisoner lists). */
  fun getPrisonRollMembers(prisonId: String): List<PrisonRollMember> {
    val members = mutableListOf<PrisonRollMember>()
    var page = 0
    do {
      val result = fetchPage(prisonId, page)
      members += result.content.map { PrisonRollMember(it.prisonerNumber, it.firstName, it.lastName) }
      page++
    } while (page < result.totalPages)
    return members
  }

  /** The names of the given prisoners, keyed by prisoner number. Empty input skips the call. */
  fun getPrisonerNames(prisonerNumbers: Collection<String>): Map<String, PrisonRollMember> {
    if (prisonerNumbers.isEmpty()) return emptyMap()
    return webClient
      .post()
      .uri("/prisoner-search/prisoner-numbers")
      .bodyValue(PrisonerNumbersRequest(prisonerNumbers.distinct()))
      .retrieve()
      .bodyToMono<List<PrisonRollEntry>>()
      .block()
      .orEmpty()
      .associate { it.prisonerNumber to PrisonRollMember(it.prisonerNumber, it.firstName, it.lastName) }
  }

  private fun fetchPage(prisonId: String, page: Int): PrisonRollPage = webClient
    .get()
    .uri(
      "/prisoner-search/prison/{prisonId}?page={page}&size={size}&responseFields=prisonerNumber,firstName,lastName",
      mapOf("prisonId" to prisonId, "page" to page, "size" to PAGE_SIZE),
    )
    .retrieve()
    .bodyToMono<PrisonRollPage>()
    .block()!!

  private companion object {
    private const val PAGE_SIZE = 2000
  }
}

data class PrisonerNumbersRequest(val prisonerNumbers: List<String>)

/** A member of a prison's roll: their number and name. */
data class PrisonRollMember(
  val prisonerNumber: String,
  val firstName: String?,
  val lastName: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonRollPage(
  val content: List<PrisonRollEntry> = emptyList(),
  val totalElements: Long = 0,
  val totalPages: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonRollEntry(
  val prisonerNumber: String,
  val firstName: String? = null,
  val lastName: String? = null,
)
