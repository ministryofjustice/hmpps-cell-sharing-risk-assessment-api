package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.health

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.hmpps.kotlin.health.HealthPingCheck

@Component("prisonerSearchApi")
class PrisonerSearchApiHealth(@Qualifier("prisonerSearchHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)
