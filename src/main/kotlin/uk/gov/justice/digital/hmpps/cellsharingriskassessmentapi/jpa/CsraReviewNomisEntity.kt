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

  @Enumerated(EnumType.STRING)
  var evaluationResultCode: CsraEvaluationResultCode? = null,

  var comment: String? = null,
  var reviewComment: String? = null,
  var reviewCommitteeComment: String? = null,

  var placementPrisonId: String? = null,
  var reviewPlacementPrisonId: String? = null,

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "review_details", columnDefinition = "jsonb")
  var reviewDetails: List<CsraReviewDetailDto> = emptyList(),

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
}
