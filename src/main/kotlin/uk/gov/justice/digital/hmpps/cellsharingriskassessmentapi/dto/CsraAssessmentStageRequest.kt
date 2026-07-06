package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

/**
 * The whole answer set submitted for one stage (provisional or final) of a new-model initial CSRA
 * assessment. The nullable answer columns allow Day 1 (provisional) to leave questions unanswered; the
 * confirming officer is taken from the authenticated user, not this request.
 */
@Schema(description = "The answers and rating confirmed for one stage of an initial CSRA assessment")
data class CsraAssessmentStageRequest(
  @param:Schema(description = "The rating being confirmed for this stage", example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val rating: CsraResult,

  @param:Schema(description = "The prison the assessment is being made at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @param:Schema(description = "The assessment comment explaining the outcome", example = "PNC checked. No issues found.", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val assessmentComment: String,

  // Evidence sources checked (null = not answered)
  @param:Schema(description = "Whether DPS adjudications were checked")
  val dpsChecked: Boolean? = null,

  @param:Schema(description = "Whether the Person Escort Record (PER) was checked")
  val perChecked: Boolean? = null,

  @param:Schema(description = "Whether the warrant was checked")
  val warrantChecked: Boolean? = null,

  @param:Schema(description = "Whether PNC was checked")
  val pncChecked: Boolean? = null,

  // Offence flags — "is there any evidence of…" (null = not answered)
  @param:Schema(description = "Evidence of murder, manslaughter or a life-threatening assault on another prisoner (mandatory high-risk trigger)")
  val offenceMurderManslaughter: Boolean? = null,

  @param:Schema(description = "Evidence of assisting a suicide in custody (mandatory high-risk trigger)")
  val offenceAssistingSuicide: Boolean? = null,

  @param:Schema(description = "Evidence of sexual assault of a same-sex adult victim (mandatory high-risk trigger)")
  val offenceSexualAssault: Boolean? = null,

  @param:Schema(description = "Evidence of repeated violence in custody")
  val offenceRepeatedViolence: Boolean? = null,

  @param:Schema(description = "Evidence of offending or behaviour motivated by prejudice")
  val offencePrejudiceMotivated: Boolean? = null,

  @param:Schema(description = "Evidence of arson or fire setting")
  val offenceArson: Boolean? = null,

  @param:Schema(description = "Evidence of kidnap, hostage taking or false imprisonment")
  val offenceKidnapHostage: Boolean? = null,

  // Prisoner conversation and vulnerability
  @param:Schema(description = "Whether an officer has had a conversation with the prisoner about sharing a cell")
  val officerSpokeToPrisoner: Boolean? = null,

  @param:Schema(description = "Whether the prisoner is likely to cause harm to a cellmate")
  val likelyToHarmCellmate: Boolean? = null,

  @param:Schema(description = "Whether the prisoner is significantly vulnerable to assault by others")
  val significantlyVulnerable: Boolean? = null,

  // Officer observation / other indicators
  @param:Schema(description = "Whether observed behaviour gives cause for concern about sharing a cell")
  val causeForConcernSharing: Boolean? = null,

  @param:Schema(description = "Whether there are any other indicators the prisoner is high risk")
  val otherHighRiskIndicators: Boolean? = null,

  // Healthcare assessment
  @param:Schema(description = "Whether the prisoner has been seen by healthcare")
  val seenByHealthcare: Boolean? = null,

  @param:Schema(description = "Whether healthcare identified signs of increased risk")
  val healthcareIncreasedRisk: Boolean? = null,

  @param:Schema(description = "For a high-risk rating, who the prisoner is a risk to")
  @field:Valid
  val riskTo: List<CsraRiskToDetail> = emptyList(),

  @param:Schema(description = "For a high-risk rating, the groups the prisoner is vulnerable due to")
  @field:Valid
  val vulnerabilities: List<CsraVulnerabilityDetail> = emptyList(),
)
