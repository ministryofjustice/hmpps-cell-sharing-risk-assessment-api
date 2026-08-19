package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

/**
 * The partial answer set for one stage of a new-model initial CSRA assessment. Carries the current
 * state of all question pages without requiring a rating or assessment comment, so an in-progress
 * task list can be saved and resumed. The confirming officer is taken from the authenticated user,
 * not this request.
 *
 * Every answer is nullable: null means "not yet answered". Replace semantics — the whole current
 * answer state for the stage is sent and replaces whatever was there before, which is how Change
 * links on the check-answers screen can clear a previously given answer.
 */
@Schema(description = "The current answers for one stage of an initial CSRA assessment, without a rating")
data class CsraAssessmentAnswersRequest(
  @param:Schema(description = "The prison the assessment is being made at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

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

  @param:Schema(description = "The evidence behind each offence answered Yes — where it was found and what it was. At most one entry per offence.")
  @field:Valid
  val offenceEvidence: List<CsraOffenceEvidence> = emptyList(),

  // Prisoner conversation and vulnerability
  @param:Schema(description = "Whether an officer has had a conversation with the prisoner about sharing a cell")
  val officerSpokeToPrisoner: Boolean? = null,

  @param:Schema(description = "Whether the prisoner is likely to cause harm to a cellmate")
  val likelyToHarmCellmate: Boolean? = null,

  @param:Schema(description = "Details of the risk, captured when likelyToHarmCellmate is true", example = "Has threatened previous cellmates.")
  val likelyToHarmCellmateDetail: String? = null,

  @param:Schema(description = "Whether the prisoner is significantly vulnerable to assault by others")
  val significantlyVulnerable: Boolean? = null,

  @param:Schema(description = "Details of the risk, captured when significantlyVulnerable is true", example = "Prisoner says they have autism and struggles with social interactions.")
  val significantlyVulnerableDetail: String? = null,

  // Officer observation / other indicators
  @param:Schema(description = "Whether observed behaviour gives cause for concern about sharing a cell")
  val causeForConcernSharing: Boolean? = null,

  @param:Schema(description = "Details of the risk, captured when causeForConcernSharing is true", example = "Aggressive towards staff on the wing.")
  val causeForConcernSharingDetail: String? = null,

  @param:Schema(description = "Whether there are any other indicators the prisoner is high risk")
  val otherHighRiskIndicators: Boolean? = null,

  @param:Schema(description = "Details of the risk, captured when otherHighRiskIndicators is true", example = "Intelligence report received from the security team.")
  val otherHighRiskIndicatorsDetail: String? = null,

  // Healthcare assessment
  @param:Schema(description = "Whether the prisoner has been seen by healthcare")
  val seenByHealthcare: Boolean? = null,

  @param:Schema(description = "Whether healthcare identified signs of increased risk")
  val healthcareIncreasedRisk: Boolean? = null,

  @param:Schema(description = "A brief summary of why healthcare consider the prisoner an increased risk, captured when healthcareIncreasedRisk is true", example = "Diagnosed autism; shared accommodation likely to cause significant distress.")
  val healthcareIncreasedRiskDetail: String? = null,

  @param:Schema(description = "For a high-risk rating, who the prisoner is a risk to")
  @field:Valid
  val riskTo: List<CsraRiskToDetail> = emptyList(),

  @param:Schema(description = "For a high-risk rating, the groups the prisoner is vulnerable due to")
  @field:Valid
  val vulnerabilities: List<CsraVulnerabilityDetail> = emptyList(),

  @param:Schema(description = "The version of the assessment answers")
  val version: Int = 1,
)
