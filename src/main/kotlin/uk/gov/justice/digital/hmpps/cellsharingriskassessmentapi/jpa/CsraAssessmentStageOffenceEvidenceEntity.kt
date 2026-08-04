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
 * The evidence behind a Yes to one of the seven offence questions on an assessment stage: where it was
 * found and a free-text description of it. At most one row per (stage, offence); 0..7 per stage.
 *
 * Unlike the answer columns on [CsraAssessmentStageEntity], the source flags are non-null. A row only
 * exists because the assessor reached the "where did you find evidence of…?" screen, so an unticked box
 * means "not this source" rather than "not answered".
 */
@Entity
@Table(name = "csra_assessment_stage_offence_evidence")
class CsraAssessmentStageOffenceEvidenceEntity(

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stage_id", nullable = false, updatable = false)
  val stage: CsraAssessmentStageEntity,

  @Enumerated(EnumType.STRING)
  var offence: CsraOffence,

  var pnc: Boolean = false,
  var warrant: Boolean = false,
  var dps: Boolean = false,
  var per: Boolean = false,
  var other: Boolean = false,

  /** Names the source when [other] is set. */
  var otherSourceDetail: String? = null,

  /** "Provide details of the evidence" — free text describing what was found. */
  var details: String? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraAssessmentStageOffenceEvidenceEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
