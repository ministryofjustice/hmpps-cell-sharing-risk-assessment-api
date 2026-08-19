package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * A snapshot of an in-progress or complete initial CSRA assessment sufficient to resume it: the
 * review's status and results, plus the full answer set for each stage that has been saved so far.
 * The UI derives task-list section state (Completed / Not yet started) from the answers.
 */
@Schema(description = "An initial CSRA assessment with the saved answers for each stage")
data class CsraAssessmentDto(
  @param:Schema(description = "The assessment id")
  val assessmentId: UUID,

  @param:Schema(description = "The prisoner number")
  val prisonerNumber: String,

  @param:Schema(description = "The prison the assessment is currently associated with")
  val prisonId: String?,

  @param:Schema(description = "The assessment lifecycle status")
  val status: CsraReviewStatus,

  @param:Schema(description = "Username of the officer who started the assessment")
  val startedBy: String,

  @param:Schema(description = "When the assessment was started")
  val startedAt: LocalDateTime,

  @param:Schema(description = "The interim (provisional Day 1) rating, if the provisional stage has been confirmed")
  val interimResult: CsraResult?,

  @param:Schema(description = "The final (Day 2) rating, if the final stage has been confirmed")
  val finalResult: CsraResult?,

  @param:Schema(description = "The saved answer sets, one per stage that has been written to. Empty for a brand-new assessment.")
  val stages: List<CsraAssessmentStageAnswersDto>,
)

/**
 * The answer set saved for one stage of an initial CSRA assessment. Answer fields are null when
 * not yet answered. lastSavedBy and lastSavedAt are from the most recent partial save; they are
 * null for a stage that has only ever been written by a full confirm (PUT .../provisional or
 * .../final), in which case the stage was captured in a single sitting.
 */
@Schema(description = "The saved answers for one stage of an initial CSRA assessment")
data class CsraAssessmentStageAnswersDto(
  @param:Schema(description = "Which stage these answers belong to")
  val stage: CsraAssessmentStage,

  @param:Schema(description = "The prison this stage was captured at")
  val prisonId: String?,

  @param:Schema(description = "Username of the officer who last partially saved this stage, if any")
  val lastSavedBy: String?,

  @param:Schema(description = "When this stage was last partially saved, if ever")
  val lastSavedAt: LocalDateTime?,

  // Evidence sources checked (null = not answered)
  val dpsChecked: Boolean?,
  val perChecked: Boolean?,
  val warrantChecked: Boolean?,
  val pncChecked: Boolean?,

  // Offence flags (null = not answered)
  val offenceMurderManslaughter: Boolean?,
  val offenceAssistingSuicide: Boolean?,
  val offenceSexualAssault: Boolean?,
  val offenceRepeatedViolence: Boolean?,
  val offencePrejudiceMotivated: Boolean?,
  val offenceArson: Boolean?,
  val offenceKidnapHostage: Boolean?,

  val offenceEvidence: List<CsraOffenceEvidence>,

  // Prisoner conversation and vulnerability (null = not answered)
  val officerSpokeToPrisoner: Boolean?,
  val likelyToHarmCellmate: Boolean?,
  val likelyToHarmCellmateDetail: String?,
  val significantlyVulnerable: Boolean?,
  val significantlyVulnerableDetail: String?,

  // Officer observation / other indicators (null = not answered)
  val causeForConcernSharing: Boolean?,
  val causeForConcernSharingDetail: String?,
  val otherHighRiskIndicators: Boolean?,
  val otherHighRiskIndicatorsDetail: String?,

  // Healthcare assessment (null = not answered)
  val seenByHealthcare: Boolean?,
  val healthcareIncreasedRisk: Boolean?,
  val healthcareIncreasedRiskDetail: String?,

  val riskTo: List<CsraRiskToDetail>,
  val vulnerabilities: List<CsraVulnerabilityDetail>,

  val version: Int,
)

fun CsraReviewEntity.toAssessmentDto(stages: List<CsraAssessmentStageEntity>) = CsraAssessmentDto(
  assessmentId = id!!,
  prisonerNumber = prisonerNumber,
  prisonId = prisonId,
  status = status,
  startedBy = createdBy,
  startedAt = createdAt,
  interimResult = interimResult,
  finalResult = finalResult,
  stages = stages.map { it.toStageAnswersDto() },
)

fun CsraAssessmentStageEntity.toStageAnswersDto() = CsraAssessmentStageAnswersDto(
  stage = stage,
  prisonId = prisonId,
  lastSavedBy = lastSavedBy,
  lastSavedAt = lastSavedAt,
  dpsChecked = dpsChecked,
  perChecked = perChecked,
  warrantChecked = warrantChecked,
  pncChecked = pncChecked,
  offenceMurderManslaughter = offenceMurderManslaughter,
  offenceAssistingSuicide = offenceAssistingSuicide,
  offenceSexualAssault = offenceSexualAssault,
  offenceRepeatedViolence = offenceRepeatedViolence,
  offencePrejudiceMotivated = offencePrejudiceMotivated,
  offenceArson = offenceArson,
  offenceKidnapHostage = offenceKidnapHostage,
  offenceEvidence = offenceEvidence.map { it.toDto() },
  officerSpokeToPrisoner = officerSpokeToPrisoner,
  likelyToHarmCellmate = likelyToHarmCellmate,
  likelyToHarmCellmateDetail = likelyToHarmCellmateDetail,
  significantlyVulnerable = significantlyVulnerable,
  significantlyVulnerableDetail = significantlyVulnerableDetail,
  causeForConcernSharing = causeForConcernSharing,
  causeForConcernSharingDetail = causeForConcernSharingDetail,
  otherHighRiskIndicators = otherHighRiskIndicators,
  otherHighRiskIndicatorsDetail = otherHighRiskIndicatorsDetail,
  seenByHealthcare = seenByHealthcare,
  healthcareIncreasedRisk = healthcareIncreasedRisk,
  healthcareIncreasedRiskDetail = healthcareIncreasedRiskDetail,
  riskTo = riskTo.map { CsraRiskToDetail(category = it.category, details = it.details) },
  vulnerabilities = vulnerabilities.map { CsraVulnerabilityDetail(category = it.category, details = it.details) },
  version = version,
)
