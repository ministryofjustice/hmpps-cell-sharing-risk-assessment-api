package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraReviewDetailDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The additional legacy NOMIS data for a migrated/synchronised CSRA review.
 *
 * Has a 1:0..1 relationship with [CsraReviewEntity] and holds only the NOMIS fields that the core
 * record does not already capture, keeping the raw NOMIS values verbatim. The question/answer detail
 * is stored as an opaque JSONB blob and deserialized only when needed.
 */
@Entity
@Table(name = "csra_review_nomis")
class CsraReviewNomisEntity(

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "csra_review_id", nullable = false, updatable = false)
  val csraReview: CsraReviewEntity,

  var score: BigDecimal? = null,

  @Enumerated(EnumType.STRING)
  var status: CsraStatus? = null,

  @Enumerated(EnumType.STRING)
  var calculatedLevel: CsraLevel? = null,

  @Enumerated(EnumType.STRING)
  var reviewLevel: CsraLevel? = null,

  @Enumerated(EnumType.STRING)
  var approvedLevel: CsraLevel? = null,

  @Enumerated(EnumType.STRING)
  var committeeCode: CsraCommitteeCode? = null,

  @Enumerated(EnumType.STRING)
  var reviewCommitteeCode: CsraCommitteeCode? = null,

  var evaluationDate: LocalDate? = null,

  /**
   * The next review date NOMIS recorded on *this* review.
   *
   * Distinct from [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity],
   * which holds the single date currently in force per prisoner. Null for rows migrated before the column
   * existed — never substitute the per-prisoner date, which would date-stamp a historic review with today's.
   */
  var nextReviewDate: LocalDate? = null,

  @Enumerated(EnumType.STRING)
  var evaluationResultCode: CsraEvaluationResultCode? = null,

  var comment: String? = null,
  var reviewComment: String? = null,
  var reviewCommitteeComment: String? = null,

  var placementPrisonId: String? = null,
  var reviewPlacementPrisonId: String? = null,

  /**
   * The question/answer tree exactly as NOMIS supplied it.
   *
   * Nullable because the column is: every write path stores `[]` rather than null, but `review_details` has
   * been nullable since V2 and a row written by hand or by a data fix would otherwise put a null into a
   * non-null property and fail the whole read with a 500. Read it through [reviewDetailsOrEmpty].
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "review_details", columnDefinition = "jsonb")
  var reviewDetails: List<CsraReviewDetailDto>? = emptyList(),

  /**
   * When this row was last written by migration or sync — our wall clock, not NOMIS's.
   *
   * The core record's `createdAt` is NOMIS's own creation timestamp (which is why it goes back to 2006),
   * and nothing else records when the data actually reached us. Without this, questions like "which
   * migration run produced the current state" are unanswerable, which has already cost a day's
   * investigation once. Null means the row predates this column.
   */
  var ingestedAt: LocalDateTime? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraReviewNomisEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()

  /** The stored question/answer tree, treating a null column as "nothing captured". */
  val reviewDetailsOrEmpty: List<CsraReviewDetailDto> get() = reviewDetails.orEmpty()
}
