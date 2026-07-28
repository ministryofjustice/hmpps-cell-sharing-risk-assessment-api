package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.AgencyStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.ActiveAgencyEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.ActiveAgencyRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ActiveAgenciesServiceTest {

  private val clock: Clock = Clock.fixed(Instant.parse("2023-12-05T12:34:56Z"), ZoneId.of("Europe/London"))
  private val activeAgencyRepository: ActiveAgencyRepository = mock()
  private val prisonRegisterClient: PrisonRegisterClient = mock()
  private val service = ActiveAgenciesService(activeAgencyRepository, prisonRegisterClient, clock)

  private fun agency(agencyId: String, active: Boolean = true) = ActiveAgencyEntity(
    agencyId = agencyId,
    active = active,
    updatedAt = LocalDateTime.now(clock),
    updatedBy = "AN_ADMIN",
  )

  @Test
  fun `returns the active agency ids sorted`() {
    whenever(activeAgencyRepository.findAllByActiveTrue()).thenReturn(listOf(agency("MDI"), agency("LEI")))

    assertThat(service.getActiveAgencies()).containsExactly("LEI", "MDI")
  }

  @Test
  fun `lists every operational prison, name-resolved and sorted by name`() {
    whenever(activeAgencyRepository.findAllByActiveTrue()).thenReturn(listOf(agency("MDI")))
    whenever(prisonRegisterClient.getActivePrisonIds()).thenReturn(setOf("MDI", "LEI"))
    whenever(prisonRegisterClient.getPrisonNames())
      .thenReturn(mapOf("MDI" to "Moorland (HMP)", "LEI" to "Leeds (HMP)"))

    assertThat(service.getAllAgencies()).containsExactly(
      AgencyStatus(agencyId = "LEI", name = "Leeds (HMP)", active = false),
      AgencyStatus(agencyId = "MDI", name = "Moorland (HMP)", active = true),
    )
  }

  @Test
  fun `a switched-on prison stays listed once it is no longer operational, so it can be switched off`() {
    whenever(activeAgencyRepository.findAllByActiveTrue()).thenReturn(listOf(agency("XXI")))
    whenever(prisonRegisterClient.getActivePrisonIds()).thenReturn(setOf("LEI"))
    whenever(prisonRegisterClient.getPrisonNames())
      .thenReturn(mapOf("LEI" to "Leeds (HMP)", "XXI" to "Closed (HMP)"))

    assertThat(service.getAllAgencies()).containsExactly(
      AgencyStatus(agencyId = "XXI", name = "Closed (HMP)", active = true),
      AgencyStatus(agencyId = "LEI", name = "Leeds (HMP)", active = false),
    )
  }

  @Test
  fun `falls back to the agency id when prison-register has no name`() {
    whenever(activeAgencyRepository.findAllByActiveTrue()).thenReturn(emptyList())
    whenever(prisonRegisterClient.getActivePrisonIds()).thenReturn(setOf("LEI"))
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(emptyMap())

    assertThat(service.getAllAgencies()).containsExactly(AgencyStatus(agencyId = "LEI", name = "LEI", active = false))
  }

  @Test
  fun `is active only for a prison with an active row`() {
    whenever(activeAgencyRepository.findByAgencyId("LEI")).thenReturn(agency("LEI"))
    whenever(activeAgencyRepository.findByAgencyId("MDI")).thenReturn(agency("MDI", active = false))
    whenever(activeAgencyRepository.findByAgencyId("XXI")).thenReturn(null)

    assertThat(service.isActive("LEI")).isTrue()
    assertThat(service.isActive("MDI")).isFalse()
    assertThat(service.isActive("XXI")).isFalse()
  }

  @Test
  fun `switching on a prison for the first time creates a row stamped with the admin`() {
    whenever(activeAgencyRepository.findByAgencyId("LEI")).thenReturn(null)
    whenever(activeAgencyRepository.save(any<ActiveAgencyEntity>())).thenAnswer { it.arguments[0] }
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(mapOf("LEI" to "Leeds (HMP)"))

    val result = service.setActive("LEI", active = true, username = "AN_ADMIN")

    assertThat(result).isEqualTo(AgencyStatus(agencyId = "LEI", name = "Leeds (HMP)", active = true))
  }

  @Test
  fun `switching off an existing prison updates the row rather than creating another`() {
    val existing = agency("LEI")
    whenever(activeAgencyRepository.findByAgencyId("LEI")).thenReturn(existing)
    whenever(activeAgencyRepository.save(any<ActiveAgencyEntity>())).thenAnswer { it.arguments[0] }
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(mapOf("LEI" to "Leeds (HMP)"))

    val result = service.setActive("LEI", active = false, username = "ANOTHER_ADMIN")

    assertThat(result.active).isFalse()
    assertThat(existing.active).isFalse()
    assertThat(existing.updatedBy).isEqualTo("ANOTHER_ADMIN")
    assertThat(existing.updatedAt).isEqualTo(LocalDateTime.now(clock))
  }
}
