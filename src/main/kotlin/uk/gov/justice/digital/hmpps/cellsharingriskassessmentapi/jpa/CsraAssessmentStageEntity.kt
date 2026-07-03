package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDateTime
import java.util.UUID

/**
 * The captured answer set for one stage of a new (DPS) initial CSRA assessment.
 *
 * Has a 1:0..2 relationship with [CsraReviewEntity]: a review may have a PROVISIONAL (Day 1) and/or a
 * FINAL (Day 2) stage, unique per [stage]. Answer columns are deliberately typed and nullable — a null
 * means "not answered", which Day 1 legitimately allows. The stage's rating is not duplicated here; it
 * is derived from the review's interim (provisional) / final result, the single source of truth.
 */
@Entity
@Table(name = "csra_assessment_stage")
class CsraAssessmentStageEntity(

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "csra_review_id", nullable = false, updatable = false)
  val csraReview: CsraReviewEntity,

  @Enumerated(EnumType.STRING)
  var stage: CsraAssessmentStage,

  var completedBy: String? = null,
  var completedAt: LocalDateTime? = null,
  var prisonId: String? = null,
  var assessmentComment: String? = null,
  var questionSetVersion: Int? = null,

  // Evidence sources checked (null = not answered)
  var dpsChecked: Boolean? = null,
  var perChecked: Boolean? = null,
  var warrantChecked: Boolean? = null,
  var pncChecked: Boolean? = null,

  // Offence flags — "is there any evidence of…" (null = not answered)
  var offenceMurderManslaughter: Boolean? = null,
  var offenceAssistingSuicide: Boolean? = null,
  var offenceSexualAssault: Boolean? = null,
  var offenceRepeatedViolence: Boolean? = null,
  var offencePrejudiceMotivated: Boolean? = null,
  var offenceArson: Boolean? = null,
  var offenceKidnapHostage: Boolean? = null,

  // Prisoner conversation and vulnerability
  var officerSpokeToPrisoner: Boolean? = null,
  var likelyToHarmCellmate: Boolean? = null,
  var significantlyVulnerable: Boolean? = null,

  // Officer observation / other indicators
  var causeForConcernSharing: Boolean? = null,
  var otherHighRiskIndicators: Boolean? = null,

  // Healthcare assessment
  var seenByHealthcare: Boolean? = null,
  var healthcareIncreasedRisk: Boolean? = null,

  @OneToMany(mappedBy = "stage", cascade = [CascadeType.ALL], orphanRemoval = true)
  val riskTo: MutableList<CsraAssessmentStageRiskToEntity> = mutableListOf(),

  @OneToMany(mappedBy = "stage", cascade = [CascadeType.ALL], orphanRemoval = true)
  val vulnerabilities: MutableList<CsraAssessmentStageVulnerabilityEntity> = mutableListOf(),

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CsraAssessmentStageEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
