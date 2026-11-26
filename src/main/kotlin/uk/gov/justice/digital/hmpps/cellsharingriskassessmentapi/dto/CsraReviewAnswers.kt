package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason

/**
 * What a reviewer has entered on a CSR review's question pages so far, before a rating is confirmed (MAPA-537).
 *
 * Saved part way through the journey so a review can be left and picked up later, and returned by the read so
 * the UI can show it again. Every answer is nullable: null means "not yet answered", or not asked by this
 * review type — which questions a type asks is set out in CsraReviewQuestionSet. The confirm page's own
 * fields (rating, review comment, MDT chair, review date, risk to and vulnerabilities) are deliberately not
 * here — the designs save nothing from that page until the rating is confirmed.
 *
 * The field names match [CsraReviewStageRequest], so the UI can spread a saved set of answers into the confirm
 * request, as it already does for assessments.
 */
@Schema(description = "The answers given so far in a CSR review, before a rating is confirmed")
data class CsraReviewAnswers(
  @param:Schema(description = "Why the review is being held. Decides which questions the review asks.", example = "SHORT_TERM_HIGH_RISK_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val reviewReason: CsraReviewReason,

  @param:Schema(description = "Whether a multidisciplinary team has reviewed the prisoner's cell sharing risk. Asked by the NEW_OR_ADDITIONAL_INFORMATION and RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING reviews only, and must be true when their final rating is confirmed.")
  val mdtReviewed: Boolean? = null,

  @param:Schema(description = "The evidence sources ticked so far. OTHER carries details naming the source.")
  @field:Valid
  val evidenceSources: List<CsraReviewEvidenceSourceSelection> = emptyList(),

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

  @param:Schema(description = "Whether there are any other indicators the prisoner is high risk")
  val otherHighRiskIndicators: Boolean? = null,
  @param:Schema(description = "Details of the other indicators")
  val otherHighRiskIndicatorsDetail: String? = null,

  // The questions only the new or additional information review (type 3) asks.
  @param:Schema(description = "Whether the prisoner was previously an in-patient at a special hospital or secure unit")
  val secureHospitalInpatient: Boolean? = null,
  @param:Schema(description = "Details of the special hospital or secure unit stay")
  val secureHospitalInpatientDetail: String? = null,

  @param:Schema(description = "Whether the prisoner has been diagnosed with a personality disorder")
  val personalityDisorder: Boolean? = null,
  @param:Schema(description = "Details of the personality disorder diagnosis")
  val personalityDisorderDetail: String? = null,

  // The questions only the recent change in behaviour or thinking review (type 4) asks.
  @param:Schema(description = "Evidence that the prisoner wants to seriously harm or kill someone")
  val wantsToHarmOrKill: Boolean? = null,
  @param:Schema(description = "Details of the evidence that the prisoner wants to seriously harm or kill someone")
  val wantsToHarmOrKillDetail: String? = null,

  @param:Schema(description = "Whether any strange or unusual behaviour has been observed")
  val strangeOrUnusualBehaviour: Boolean? = null,
  @param:Schema(description = "Details of the strange or unusual behaviour")
  val strangeOrUnusualBehaviourDetail: String? = null,

  @param:Schema(description = "Clinical evidence of paranoid behaviour, confirmed by a healthcare assessment")
  val clinicalParanoidBehaviour: Boolean? = null,
  @param:Schema(description = "Details of the clinical evidence of paranoid behaviour")
  val clinicalParanoidBehaviourDetail: String? = null,

  @param:Schema(description = "Whether the prisoner or their cellmate has frequently requested to change cell")
  val frequentCellChangeRequests: Boolean? = null,
  @param:Schema(description = "Details of the requests to change cell")
  val frequentCellChangeRequestsDetail: String? = null,
)

/**
 * A part-way save of a CSR review. The answers replace whatever was saved before, so an answer the user has
 * cleared comes back as null.
 */
@Schema(description = "A part-way save of a CSR review's answers")
data class CsraReviewAnswersRequest(
  @param:Schema(description = "The prison the review is being made at", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @param:Schema(
    description = "The version of the saved answers this save is based on, from the last read of the review. " +
      "A review with no answers saved yet is at version 1. A save based on an out-of-date version is rejected.",
    example = "1",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  val version: Int,

  @param:Schema(description = "Every answer given so far", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:Valid
  val answers: CsraReviewAnswers,
)

fun CsraReviewStageRequest.toAnswers() = CsraReviewAnswers(
  reviewReason = reviewReason,
  mdtReviewed = mdtReviewed,
  evidenceSources = evidenceSources,
  offenceMurderManslaughter = offenceMurderManslaughter,
  offenceMurderManslaughterDetail = offenceMurderManslaughterDetail,
  offenceAssistingSuicide = offenceAssistingSuicide,
  offenceAssistingSuicideDetail = offenceAssistingSuicideDetail,
  offenceSexualAssault = offenceSexualAssault,
  offenceSexualAssaultDetail = offenceSexualAssaultDetail,
  offenceRepeatedViolence = offenceRepeatedViolence,
  offenceRepeatedViolenceDetail = offenceRepeatedViolenceDetail,
  offencePrejudiceMotivated = offencePrejudiceMotivated,
  offencePrejudiceMotivatedDetail = offencePrejudiceMotivatedDetail,
  offenceArson = offenceArson,
  offenceArsonDetail = offenceArsonDetail,
  offenceKidnapHostage = offenceKidnapHostage,
  offenceKidnapHostageDetail = offenceKidnapHostageDetail,
  likelyToHarmCellmate = likelyToHarmCellmate,
  likelyToHarmCellmateDetail = likelyToHarmCellmateDetail,
  significantlyVulnerable = significantlyVulnerable,
  significantlyVulnerableDetail = significantlyVulnerableDetail,
  healthcareIncreasedRisk = healthcareIncreasedRisk,
  healthcareIncreasedRiskDetail = healthcareIncreasedRiskDetail,
  otherHighRiskIndicators = otherHighRiskIndicators,
  otherHighRiskIndicatorsDetail = otherHighRiskIndicatorsDetail,
  secureHospitalInpatient = secureHospitalInpatient,
  secureHospitalInpatientDetail = secureHospitalInpatientDetail,
  personalityDisorder = personalityDisorder,
  personalityDisorderDetail = personalityDisorderDetail,
  wantsToHarmOrKill = wantsToHarmOrKill,
  wantsToHarmOrKillDetail = wantsToHarmOrKillDetail,
  strangeOrUnusualBehaviour = strangeOrUnusualBehaviour,
  strangeOrUnusualBehaviourDetail = strangeOrUnusualBehaviourDetail,
  clinicalParanoidBehaviour = clinicalParanoidBehaviour,
  clinicalParanoidBehaviourDetail = clinicalParanoidBehaviourDetail,
  frequentCellChangeRequests = frequentCellChangeRequests,
  frequentCellChangeRequestsDetail = frequentCellChangeRequestsDetail,
)

/**
 * The saved answers on a review stage. Every stage of a review is written with a reason (the save and confirm
 * requests both require one), so a missing reason means the row was not written by this service.
 */
fun CsraAssessmentStageEntity.toReviewAnswers() = CsraReviewAnswers(
  reviewReason = checkNotNull(reviewReason) { "Review stage $id has no review reason" },
  mdtReviewed = mdtReviewed,
  evidenceSources = evidenceSources
    .sortedBy { it.source.ordinal }
    .map { CsraReviewEvidenceSourceSelection(source = it.source, details = it.details) },
  offenceMurderManslaughter = offenceMurderManslaughter,
  offenceMurderManslaughterDetail = offenceMurderManslaughterDetail,
  offenceAssistingSuicide = offenceAssistingSuicide,
  offenceAssistingSuicideDetail = offenceAssistingSuicideDetail,
  offenceSexualAssault = offenceSexualAssault,
  offenceSexualAssaultDetail = offenceSexualAssaultDetail,
  offenceRepeatedViolence = offenceRepeatedViolence,
  offenceRepeatedViolenceDetail = offenceRepeatedViolenceDetail,
  offencePrejudiceMotivated = offencePrejudiceMotivated,
  offencePrejudiceMotivatedDetail = offencePrejudiceMotivatedDetail,
  offenceArson = offenceArson,
  offenceArsonDetail = offenceArsonDetail,
  offenceKidnapHostage = offenceKidnapHostage,
  offenceKidnapHostageDetail = offenceKidnapHostageDetail,
  likelyToHarmCellmate = likelyToHarmCellmate,
  likelyToHarmCellmateDetail = likelyToHarmCellmateDetail,
  significantlyVulnerable = significantlyVulnerable,
  significantlyVulnerableDetail = significantlyVulnerableDetail,
  healthcareIncreasedRisk = healthcareIncreasedRisk,
  healthcareIncreasedRiskDetail = healthcareIncreasedRiskDetail,
  otherHighRiskIndicators = otherHighRiskIndicators,
  otherHighRiskIndicatorsDetail = otherHighRiskIndicatorsDetail,
  secureHospitalInpatient = secureHospitalInpatient,
  secureHospitalInpatientDetail = secureHospitalInpatientDetail,
  personalityDisorder = personalityDisorder,
  personalityDisorderDetail = personalityDisorderDetail,
  wantsToHarmOrKill = wantsToHarmOrKill,
  wantsToHarmOrKillDetail = wantsToHarmOrKillDetail,
  strangeOrUnusualBehaviour = strangeOrUnusualBehaviour,
  strangeOrUnusualBehaviourDetail = strangeOrUnusualBehaviourDetail,
  clinicalParanoidBehaviour = clinicalParanoidBehaviour,
  clinicalParanoidBehaviourDetail = clinicalParanoidBehaviourDetail,
  frequentCellChangeRequests = frequentCellChangeRequests,
  frequentCellChangeRequestsDetail = frequentCellChangeRequestsDetail,
)
