package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.config

import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.ActiveAgenciesService

/**
 * Adds `activeAgencies` (the prisons the CSRA service is switched on for) to the actuator /info
 * payload, in the standard HMPPS shape the DPS frontend components and service catalogue read. The
 * DPS home page uses it to decide whether to show the CSRA tile.
 *
 * /info is public, so the frontend can read the rollout state without a privileged token.
 */
@Component
class ActiveAgenciesInfo(
  private val activeAgenciesService: ActiveAgenciesService,
) : InfoContributor {
  override fun contribute(builder: Info.Builder) {
    builder.withDetail("activeAgencies", activeAgenciesService.getActiveAgencies())
  }
}
