package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.health

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.hmpps.kotlin.health.HealthPingCheck

@Component("prisonApi")
class PrisonApiHealth(@Qualifier("prisonApiHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)
