package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.util.UUID

/**
 * A single evidence source selected on a review stage, with optional free text (required for
 * [CsraReviewEvidenceSource.OTHER], which names the source). 0..n per stage, one row per selected source.
 *
 * Review journey only — an initial assessment records its evidence in the four `*Checked` booleans on
 * [CsraAssessmentStageEntity].
 */
@Entity
@Table(name = "csra_assessment_stage_evidence_source")
class CsraAssessmentStageEvidenceSourceEntity(

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stage_id", nullable = false, updatable = false)
  val stage: CsraAssessmentStageEntity,

  @Enumerated(EnumType.STRING)
  var source: CsraReviewEvidenceSource,

  var details: String? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraAssessmentStageEvidenceSourceEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
