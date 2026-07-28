package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.AgencyStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.ActiveAgencyEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.ActiveAgencyRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * The set of agencies (prisons) the CSRA service is switched on for in DPS.
 *
 * Read live rather than cached: it is a tiny, indexed table, and a per-pod cache makes an admin's on/off
 * toggle appear to flip-flop as requests (notably the /info poll the frontend reads) are served by
 * different pods — this service runs four replicas.
 */
@Service
class ActiveAgenciesService(
  private val activeAgencyRepository: ActiveAgencyRepository,
  private val prisonRegisterClient: PrisonRegisterClient,
  private val clock: Clock,
) {

  fun getActiveAgencies(): List<String> = activeAgencyRepository.findAllByActiveTrue().map { it.agencyId }.sorted()

  /**
   * Every prison the admin screen can switch, with its current state.
   *
   * The operational prisons from prison-register are unioned with the currently-active set, so a prison
   * that has been switched on but has since dropped out of the operational list stays listed and can
   * still be switched off.
   */
  fun getAllAgencies(): List<AgencyStatus> {
    val active = getActiveAgencies().toSet()
    val names = prisonRegisterClient.getPrisonNames()
    return (prisonRegisterClient.getActivePrisonIds() + active)
      .map { id -> AgencyStatus(agencyId = id, name = names[id] ?: id, active = id in active) }
      .sortedBy { it.name }
  }

  fun isActive(agencyId: String): Boolean = activeAgencyRepository.findByAgencyId(agencyId)?.active == true

  /** Switches the CSRA service on or off for a prison. Idempotent. */
  @Transactional
  fun setActive(agencyId: String, active: Boolean, username: String): AgencyStatus {
    val agency = activeAgencyRepository.findByAgencyId(agencyId)
      ?.apply {
        this.active = active
        this.updatedAt = LocalDateTime.now(clock)
        this.updatedBy = username
      }
      ?: ActiveAgencyEntity(
        agencyId = agencyId,
        active = active,
        updatedAt = LocalDateTime.now(clock),
        updatedBy = username,
      )
    val saved = activeAgencyRepository.save(agency)
    val name = prisonRegisterClient.getPrisonNames()[saved.agencyId] ?: saved.agencyId
    return AgencyStatus(agencyId = saved.agencyId, name = name, active = saved.active)
  }
}
