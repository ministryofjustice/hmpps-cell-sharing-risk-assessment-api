package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The core CSRA review record owned by the new (DPS) service.
 *
 * Holds only the data common to both the new assessment journey and migrated NOMIS reviews. Legacy
 * NOMIS-only data (review question/answer detail, committee/approval data, scores, etc.) is not held
 * here and will live in a separate legacy-specific table.
 */
@Entity
@Table(name = "csra_review")
class CsraReviewEntity(

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,

  var prisonerNumber: String,

  // The NOMIS agency id where the assessment took place. Nullable now (NOMIS does not always send
  // it) but will always be recorded in the new service.
  var prisonId: String? = null,

  // The date the assessment was started.
  var assessmentDate: LocalDate,

  @Enumerated(EnumType.STRING)
  var type: CsraType,

  // The interim result issued when a review cannot be completed on the first day. Not set for
  // migrated legacy reviews.
  @Enumerated(EnumType.STRING)
  var interimResult: CsraResult? = null,
  var interimResultDate: LocalDate? = null,

  @Enumerated(EnumType.STRING)
  var finalResult: CsraResult? = null,
  var finalResultDate: LocalDate? = null,

  // Lifecycle state. New-model reviews start IN_PROGRESS; migrated legacy reviews are COMPLETE. A move
  // may close (CLOSED, rating retained) or archive (ARCHIVED, no rating) an in-progress review.
  @Enumerated(EnumType.STRING)
  var status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,

  // Set when the review was closed/archived on admission (R-01/R-02).
  @Enumerated(EnumType.STRING)
  var closureReason: CsraClosureReason? = null,
  var closedAt: LocalDateTime? = null,
  var closedBy: String? = null,

  var createdAt: LocalDateTime,
  var createdBy: String,
  var lastModifiedAt: LocalDateTime? = null,
  var lastModifiedBy: String? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraReviewEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
