package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import java.time.Duration

@Configuration
class WebClientConfiguration(
  @param:Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @param:Value("\${prison-register.url}") val prisonRegisterBaseUri: String,
  @param:Value("\${prisoner-search.url}") val prisonerSearchBaseUri: String,
  @param:Value("\${prison-api.url}") val prisonApiBaseUri: String,
  @param:Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @param:Value("\${api.timeout:20s}") val timeout: Duration,
) {
  // HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  // prison-register prison lookups are public reference data, so this client is unauthenticated.
  @Bean
  fun prisonRegisterWebClient(builder: WebClient.Builder): WebClient = builder.baseUrl(prisonRegisterBaseUri).build()

  @Bean
  fun prisonRegisterHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonRegisterBaseUri, healthTimeout)

  // prisoner-search roll lookups require an access token, so this client is authenticated via
  // OAuth2 client-credentials using the service's own registration.
  //
  // The Content-Type default is not optional: prisoner-search rejects a request without one, even on a
  // GET with no body, and reports it as a 500 "Content-Type is not supported" rather than a 415.
  @Bean
  fun prisonerSearchWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonerSearchBaseUri,
    timeout,
  )

  @Bean
  fun prisonerSearchHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonerSearchBaseUri, healthTimeout)

  // prison-api movement lookups require an access token, so this client is authenticated (the system
  // client also needs the ROLE_ESTABLISHMENT_ROLL role for the movements endpoint).
  @Bean
  fun prisonApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonApiBaseUri,
    timeout,
  )

  @Bean
  fun prisonApiHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonApiBaseUri, healthTimeout)
}
