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
 * change rarely. Any failure degrades gracefully: callers get the last good cache (or an empty map),
 * so a prison-register outage never fails a CSRA history request — names simply fall back to the id.
 */
@Component
class PrisonRegisterClient(
  @param:Qualifier("prisonRegisterWebClient") private val webClient: WebClient,
) {
  @Volatile
  private var cache: Cache? = null

  /** A map of prison id (e.g. "LEI") to prison name (e.g. "Leeds (HMP)"). */
  fun getPrisonNames(): Map<String, String> {
    cache?.takeIf { it.isFresh() }?.let { return it.names }
    // Only cache a successful fetch: a transient prison-register error must not poison names for the
    // whole TTL. On failure serve the last good cache if we have one, otherwise an empty map.
    val fetched = fetch() ?: return cache?.names.orEmpty()
    cache = Cache(fetched, Instant.now())
    return fetched
  }

  /** Drops the cached prison names so the next call re-fetches. Used by tests. */
  fun evictCache() {
    cache = null
  }

  private fun fetch(): Map<String, String>? = try {
    webClient.get()
      .uri("/prisons")
      .retrieve()
      .bodyToMono<List<PrisonDto>>()
      .block()
      ?.associate { it.prisonId to it.prisonName }
      .orEmpty()
  } catch (e: Exception) {
    log.warn("Failed to load prisons from prison-register; falling back to prison ids", e)
    null
  }

  private inner class Cache(val names: Map<String, String>, private val loadedAt: Instant) {
    fun isFresh(): Boolean = Duration.between(loadedAt, Instant.now()) < CACHE_TTL
  }

  companion object {
    private val log = LoggerFactory.getLogger(PrisonRegisterClient::class.java)
    private val CACHE_TTL: Duration = Duration.ofHours(1)
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonDto(val prisonId: String, val prisonName: String)
