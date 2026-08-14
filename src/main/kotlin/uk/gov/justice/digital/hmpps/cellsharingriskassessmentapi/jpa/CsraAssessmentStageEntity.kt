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
 * The captured answer set for one stage of a new (DPS) CSRA — both the initial assessment journey and the
 * review journey, which share this table.
 *
 * Has a 1:0..2 relationship with [CsraReviewEntity]: an assessment may have a PROVISIONAL (Day 1) and/or a
 * FINAL (Day 2) stage, a review an INTERIM and/or a FINAL, unique per [stage]. The stage's rating is not
 * duplicated here; it is derived from the review's interim (provisional) / final result, the single source
 * of truth.
 *
 * **A null answer column means one of two things.** It has always meant "not answered", which a first
 * stage legitimately allows. Since the review journey joined the table it also means "not applicable to
 * this journey" — an assessment never populates [reviewReason], [mdtChairName] or the offence detail
 * columns, and a review never populates [officerSpokeToPrisoner], [causeForConcernSharing],
 * [seenByHealthcare] or the four evidence booleans. The two are indistinguishable at column level, so
 * anything counting unanswered questions must first narrow by the parent review's [CsraReviewEntity.type]
 * (and, in time, [questionSetVersion]).
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

  // Offence flags — "is there any evidence of…" (null = not answered). The paired detail columns are the
  // review journey's free text on a Yes; an assessment records its evidence in offenceEvidence instead.
  var offenceMurderManslaughter: Boolean? = null,
  var offenceMurderManslaughterDetail: String? = null,
  var offenceAssistingSuicide: Boolean? = null,
  var offenceAssistingSuicideDetail: String? = null,
  var offenceSexualAssault: Boolean? = null,
  var offenceSexualAssaultDetail: String? = null,
  var offenceRepeatedViolence: Boolean? = null,
  var offenceRepeatedViolenceDetail: String? = null,
  var offencePrejudiceMotivated: Boolean? = null,
  var offencePrejudiceMotivatedDetail: String? = null,
  var offenceArson: Boolean? = null,
  var offenceArsonDetail: String? = null,
  var offenceKidnapHostage: Boolean? = null,
  var offenceKidnapHostageDetail: String? = null,

  // Prisoner conversation and vulnerability. A Yes to either of the latter two reveals a free-text
  // "provide details of the risk" box; officerSpokeToPrisoner is a plain yes/no and has no detail.
  var officerSpokeToPrisoner: Boolean? = null,
  var likelyToHarmCellmate: Boolean? = null,
  var likelyToHarmCellmateDetail: String? = null,
  var significantlyVulnerable: Boolean? = null,
  var significantlyVulnerableDetail: String? = null,

  // Officer observation / other indicators
  var causeForConcernSharing: Boolean? = null,
  var causeForConcernSharingDetail: String? = null,
  var otherHighRiskIndicators: Boolean? = null,
  var otherHighRiskIndicatorsDetail: String? = null,

  // Healthcare assessment
  var seenByHealthcare: Boolean? = null,
  var healthcareIncreasedRisk: Boolean? = null,
  var healthcareIncreasedRiskDetail: String? = null,

  // Review only: why the review was held, and the free-text name of who chaired the multidisciplinary
  // meeting. Captured on every confirm screen including Standard risk, so not a high-risk-only field.
  @Enumerated(EnumType.STRING)
  var reviewReason: CsraReviewReason? = null,
  var mdtChairName: String? = null,

  @OneToMany(mappedBy = "stage", cascade = [CascadeType.ALL], orphanRemoval = true)
  val offenceEvidence: MutableList<CsraAssessmentStageOffenceEvidenceEntity> = mutableListOf(),

  // Review only: the multi-select of named evidence sources. The assessment journey's equivalent is the
  // four *Checked booleans above.
  @OneToMany(mappedBy = "stage", cascade = [CascadeType.ALL], orphanRemoval = true)
  val evidenceSources: MutableList<CsraAssessmentStageEvidenceSourceEntity> = mutableListOf(),

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
