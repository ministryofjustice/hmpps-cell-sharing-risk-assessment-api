package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDateTime
import java.util.UUID

/**
 * Whether the CSRA service is switched on in DPS for an agency (prison).
 *
 * There is one row per prison the service has ever been switched on for. The row is kept when a prison
 * is switched off again rather than deleted, so the toggle is idempotent and it stays visible who last
 * changed it and when.
 *
 * The active ids are published on the public /info endpoint as `activeAgencies`, which the DPS home
 * page reads to decide whether to show the CSRA tile, and which gates the CSRA journeys so a prison
 * still managed in NOMIS cannot also be worked in DPS.
 */
@Entity
@Table(name = "active_agency")
class ActiveAgencyEntity(

  var agencyId: String,

  var active: Boolean,

  var updatedAt: LocalDateTime,
  var updatedBy: String? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as ActiveAgencyEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
