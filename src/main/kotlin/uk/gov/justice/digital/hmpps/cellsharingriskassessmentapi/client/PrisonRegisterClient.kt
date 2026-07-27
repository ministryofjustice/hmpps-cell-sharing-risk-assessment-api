package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.time.Instant

/**
 * Reads prison reference data from prison-register-api. The prison lookup is public reference data, so
 * the call is unauthenticated (see WebClientConfiguration.prisonRegisterWebClient).
 *
 * The full prison list is fetched in one call and cached in-process for a short TTL, since prisons
 * change rarely. Both views onto it (names, and the operational ids) are derived from that one cached
 * list, so a caller never triggers a second fetch. Any failure degrades gracefully: callers get the
 * last good cache (or an empty result), so a prison-register outage never fails a CSRA history
 * request — names simply fall back to the id.
 */
@Component
class PrisonRegisterClient(
  @param:Qualifier("prisonRegisterWebClient") private val webClient: WebClient,
) {
  @Volatile
  private var cache: Cache? = null

  /** A map of prison id (e.g. "LEI") to prison name (e.g. "Leeds (HMP)"). */
  fun getPrisonNames(): Map<String, String> = prisons().associate { it.prisonId to it.prisonName }

  /**
   * The ids of the prisons prison-register still holds as operational, so the rollout admin screen does
   * not offer closed ones. See ActiveAgenciesService.getAllAgencies, which keeps an already-switched-on
   * prison listed even once it drops out of this set.
   */
  fun getActivePrisonIds(): Set<String> = prisons().filter { it.active }.map { it.prisonId }.toSet()

  /** Drops the cached prisons so the next call re-fetches. Used by tests. */
  fun evictCache() {
    cache = null
  }

  private fun prisons(): List<PrisonDto> {
    cache?.takeIf { it.isFresh() }?.let { return it.prisons }
    // Only cache a successful fetch: a transient prison-register error must not poison the list for the
    // whole TTL. On failure serve the last good cache if we have one, otherwise nothing.
    val fetched = fetch() ?: return cache?.prisons.orEmpty()
    cache = Cache(fetched, Instant.now())
    return fetched
  }

  private fun fetch(): List<PrisonDto>? = try {
    webClient.get()
      .uri("/prisons")
      .retrieve()
      .bodyToMono<List<PrisonDto>>()
      .block()
      .orEmpty()
  } catch (e: Exception) {
    log.warn("Failed to load prisons from prison-register; falling back to prison ids", e)
    null
  }

  private inner class Cache(val prisons: List<PrisonDto>, private val loadedAt: Instant) {
    fun isFresh(): Boolean = Duration.between(loadedAt, Instant.now()) < CACHE_TTL
  }

  companion object {
    private val log = LoggerFactory.getLogger(PrisonRegisterClient::class.java)
    private val CACHE_TTL: Duration = Duration.ofHours(1)
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonDto(
  val prisonId: String,
  val prisonName: String,
  // Whether prison-register still holds the prison as operational. Defaulted true so an older
  // prison-register response without the field does not silently empty the operational list.
  val active: Boolean = true,
)
