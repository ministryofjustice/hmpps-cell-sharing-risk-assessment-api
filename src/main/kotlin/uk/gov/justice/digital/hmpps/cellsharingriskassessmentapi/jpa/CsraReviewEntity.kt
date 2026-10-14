package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
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

  // The agency id where the assessment took place — for a multi-stage assessment, its latest stage (final
  // if there is one, otherwise provisional). Nullable only because NOMIS does not always send it; the new
  // service requires it on every write path, so a null here identifies a migrated row.
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

  // The next review date this review set. The prisoner's current date (csra_next_review) is derived from
  // these, so a merge or booking move that changes which reviews a prisoner holds can re-derive it. Null on
  // NOMIS reviews loaded before the column existed — see [nextReviewDateOrNomis].
  var nextReviewDate: LocalDate? = null,

  // Lifecycle state. New-model reviews start IN_PROGRESS; migrated legacy reviews are COMPLETE. A move
  // may close (CLOSED, rating retained) or archive (ARCHIVED, no rating) an in-progress review.
  // A user may also archive an unrated in-progress initial assessment by cancelling it.
  @Enumerated(EnumType.STRING)
  var status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,

  // Closure reason, time and actor: set on admission (R-01/R-02), or when a user cancels an unrated
  // initial assessment (CANCELLED). The actor is the system for admission, or the cancelling user.
  @Enumerated(EnumType.STRING)
  var closureReason: CsraClosureReason? = null,
  var closedAt: LocalDateTime? = null,
  var closedBy: String? = null,

  // Set when a readmission after release ended the custody period this review belongs to (R-01). A
  // superseded review still appears in the prisoner's history but can never set their current rating.
  var supersededAt: LocalDateTime? = null,

  var createdAt: LocalDateTime,
  var createdBy: String,
  var lastModifiedAt: LocalDateTime? = null,
  var lastModifiedBy: String? = null,

  // Optimistic locking (MAPA-437). Every writer - the user journeys, the movement and merge listeners, the
  // NOMIS sync - saves the whole row, so without this the second of two concurrent saves silently undid
  // the first. Now it fails with ObjectOptimisticLockingFailureException: a 409 over HTTP, a retried
  // message on SQS.
  @Version
  var version: Int = 0,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraReviewEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}

/**
 * The next review date this review set, falling back to the NOMIS copy for a NOMIS review loaded before the
 * date was stored on the review itself (SDIT-4297). The fallback spares rewriting millions of migrated rows;
 * a review synchronised since carries the same value in both places. [nomis] is only called when the review
 * has no date of its own, so a caller can pass a lookup without paying for it on every review.
 */
fun CsraReviewEntity.nextReviewDateOrNomis(nomis: () -> CsraReviewNomisEntity?): LocalDate? = nextReviewDate ?: nomis()?.nextReviewDate
