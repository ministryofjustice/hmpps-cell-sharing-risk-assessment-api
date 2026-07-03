package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The single current "next review due" date for a prisoner.
 *
 * There is one row per prisoner: a prisoner accumulates many CSRA reviews over time, but only ever has
 * one outstanding next review date, recalculated/overwritten on each review. It is set by the latest
 * review (the DPS review journey and, for now, the NOMIS migration/sync path) and drives the
 * "high risk prisoners due for review" worklist.
 */
@Entity
@Table(name = "csra_next_review")
class CsraNextReviewEntity(

  var prisonerNumber: String,

  var nextReviewDate: LocalDate? = null,

  // The review that last set this date.
  var setByReviewId: UUID,

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
    other as CsraNextReviewEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
