package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEvidenceSourceEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import java.time.LocalDate

/**
 * The whole answer set submitted for one stage (interim or final) of a new-model CSR review.
 *
 * Every one of the eleven questions is a Yes/No that carries free text on a Yes — that is the structural
 * difference from the initial assessment, whose offence questions are plain booleans backed by a separate
 * "where did you find the evidence" capture. The confirming reviewer is taken from the authenticated user,
 * not this request.
 */
@Schema(description = "The answers and rating confirmed for one stage of a CSR review")
data class CsraReviewStageRequest(
  @param:Schema(description = "The rating being confirmed for this stage", example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val rating: CsraResult,

  @param:Schema(description = "The prison the review is being made at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @param:Schema(description = "The review comment explaining the outcome", example = "Reviewed following a change in behaviour. No further concerns.", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val reviewComment: String,

  @param:Schema(description = "Why the review was held", example = "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val reviewReason: CsraReviewReason,

  @param:Schema(description = "The name of who chaired the multidisciplinary meeting. Captured for every rating, including Standard risk.", example = "Sue Carter", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val mdtChairName: String,

  @param:Schema(description = "The evidence sources considered. OTHER must carry details naming the source.")
  @field:Valid
  val evidenceSources: List<CsraReviewEvidenceSourceSelection> = emptyList(),

  // The seven offence questions — "is there any evidence of…". A Yes must carry its detail.
  @param:Schema(description = "Evidence of murder or manslaughter")
  val offenceMurderManslaughter: Boolean? = null,
  @param:Schema(description = "Details of the murder or manslaughter evidence")
  val offenceMurderManslaughterDetail: String? = null,

  @param:Schema(description = "Evidence of assisting suicide")
  val offenceAssistingSuicide: Boolean? = null,
  @param:Schema(description = "Details of the assisting suicide evidence")
  val offenceAssistingSuicideDetail: String? = null,

  @param:Schema(description = "Evidence of sexual assault")
  val offenceSexualAssault: Boolean? = null,
  @param:Schema(description = "Details of the sexual assault evidence")
  val offenceSexualAssaultDetail: String? = null,

  @param:Schema(description = "Evidence of repeated violence")
  val offenceRepeatedViolence: Boolean? = null,
  @param:Schema(description = "Details of the repeated violence evidence")
  val offenceRepeatedViolenceDetail: String? = null,

  @param:Schema(description = "Evidence of prejudice-motivated offending")
  val offencePrejudiceMotivated: Boolean? = null,
  @param:Schema(description = "Details of the prejudice-motivated offending evidence")
  val offencePrejudiceMotivatedDetail: String? = null,

  @param:Schema(description = "Evidence of arson")
  val offenceArson: Boolean? = null,
  @param:Schema(description = "Details of the arson evidence")
  val offenceArsonDetail: String? = null,

  @param:Schema(description = "Evidence of kidnap or hostage-taking")
  val offenceKidnapHostage: Boolean? = null,
  @param:Schema(description = "Details of the kidnap or hostage-taking evidence")
  val offenceKidnapHostageDetail: String? = null,

  // The remaining four questions, which share their columns with the assessment journey.
  @param:Schema(description = "Whether the prisoner has said anything to indicate they are an increased risk when sharing a cell")
  val likelyToHarmCellmate: Boolean? = null,
  @param:Schema(description = "Details of what the prisoner said")
  val likelyToHarmCellmateDetail: String? = null,

  @param:Schema(description = "Whether the prisoner is significantly vulnerable to assault by others")
  val significantlyVulnerable: Boolean? = null,
  @param:Schema(description = "Details of the vulnerability")
  val significantlyVulnerableDetail: String? = null,

  @param:Schema(description = "Whether a healthcare assessment indicates the prisoner is an increased risk")
  val healthcareIncreasedRisk: Boolean? = null,
  @param:Schema(description = "Details of the healthcare assessment")
  val healthcareIncreasedRiskDetail: String? = null,

  @param:Schema(description = "Whether there are any other indicators the prisoner is high risk. Includes officer observation.")
  val otherHighRiskIndicators: Boolean? = null,
  @param:Schema(description = "Details of the other indicators")
  val otherHighRiskIndicatorsDetail: String? = null,

  @param:Schema(description = "Who the prisoner is a risk to. Required for a HIGH_SPECIFIC rating and rejected for any other; use the NONE category for 'no identified risk to any of these groups'.")
  @field:Valid
  val riskTo: List<CsraRiskToDetail> = emptyList(),

  @param:Schema(description = "The vulnerable or at-risk groups the prisoner belongs to. Required for a HIGH_SPECIFIC rating and rejected for any other; use the NONE category for 'no identified vulnerabilities'.")
  @field:Valid
  val vulnerabilities: List<CsraVulnerabilityDetail> = emptyList(),

  @param:Schema(description = "When the prisoner's next review is due. Chosen by the reviewer rather than computed, unlike the assessment journey. Captured for high-risk ratings only and must be in the future.", example = "2027-07-03")
  val nextReviewDate: LocalDate? = null,
)

@Schema(description = "An evidence source considered during a review")
data class CsraReviewEvidenceSourceSelection(
  @param:Schema(description = "The source", example = "OASYS", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val source: CsraReviewEvidenceSource,

  @param:Schema(description = "Free text naming the source. Required for OTHER, unused otherwise.", example = "Wing intelligence report")
  val details: String? = null,
)

fun CsraReviewEvidenceSourceSelection.toEntity(stage: CsraAssessmentStageEntity) = CsraAssessmentStageEvidenceSourceEntity(
  stage = stage,
  source = source,
  details = details,
)
