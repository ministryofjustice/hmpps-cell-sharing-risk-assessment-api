package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentTypeBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A prisoner's single current CSRA rating — the stateful source of truth for "current rating" (R-06/R-07).
 *
 * One row per prisoner. It changes only when a rating is saved (assessment/review/migration) or a
 * readmission after release resets it to "No rating" (R-01); it deliberately persists when a new
 * assessment is merely started. `rating == null` means "No rating". Denormalised (rating/provisional/
 * assessmentType/ratingDate) so prison-scoped reads are single-table lookups; `setByReviewId` points at the
 * review that set it (null on a No-rating reset) for loading the richer detail.
 */
@Entity
@Table(name = "csra_current_rating")
class CsraCurrentRatingEntity(

  var prisonerNumber: String,

  @Enumerated(EnumType.STRING)
  var rating: CsraResult? = null,

  var provisional: Boolean = false,

  @Enumerated(EnumType.STRING)
  var assessmentType: CsraAssessmentTypeBucket? = null,

  var ratingDate: LocalDate? = null,

  // The review that set this rating; null when reset to No rating on readmission.
  var setByReviewId: UUID? = null,

  @Enumerated(EnumType.STRING)
  var setReason: CsraRatingSetReason,

  var setAt: LocalDateTime,
  var setBy: String? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraCurrentRatingEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
