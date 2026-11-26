package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewAnswers
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason.NEW_OR_ADDITIONAL_INFORMATION
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason.RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason.SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason.SHORT_TERM_HIGH_RISK_REVIEW
import kotlin.reflect.KProperty1

/**
 * Every Yes/No question a CSR review can ask, each with the free-text detail a Yes reveals. Listing them once
 * lets the rules below treat every question alike, instead of naming all seventeen in each rule.
 *
 * [field] is the request field name, which is what an error lists.
 */
enum class CsraReviewQuestion(
  val answer: KProperty1<CsraReviewAnswers, Boolean?>,
  val detail: KProperty1<CsraReviewAnswers, String?>,
) {
  MURDER_MANSLAUGHTER(CsraReviewAnswers::offenceMurderManslaughter, CsraReviewAnswers::offenceMurderManslaughterDetail),
  ASSISTING_SUICIDE(CsraReviewAnswers::offenceAssistingSuicide, CsraReviewAnswers::offenceAssistingSuicideDetail),
  SEXUAL_ASSAULT(CsraReviewAnswers::offenceSexualAssault, CsraReviewAnswers::offenceSexualAssaultDetail),
  REPEATED_VIOLENCE(CsraReviewAnswers::offenceRepeatedViolence, CsraReviewAnswers::offenceRepeatedViolenceDetail),
  PREJUDICE_MOTIVATED(CsraReviewAnswers::offencePrejudiceMotivated, CsraReviewAnswers::offencePrejudiceMotivatedDetail),
  ARSON(CsraReviewAnswers::offenceArson, CsraReviewAnswers::offenceArsonDetail),
  KIDNAP_HOSTAGE(CsraReviewAnswers::offenceKidnapHostage, CsraReviewAnswers::offenceKidnapHostageDetail),
  LIKELY_TO_HARM_CELLMATE(CsraReviewAnswers::likelyToHarmCellmate, CsraReviewAnswers::likelyToHarmCellmateDetail),
  SIGNIFICANTLY_VULNERABLE(CsraReviewAnswers::significantlyVulnerable, CsraReviewAnswers::significantlyVulnerableDetail),
  HEALTHCARE_INCREASED_RISK(CsraReviewAnswers::healthcareIncreasedRisk, CsraReviewAnswers::healthcareIncreasedRiskDetail),
  SECURE_HOSPITAL_INPATIENT(CsraReviewAnswers::secureHospitalInpatient, CsraReviewAnswers::secureHospitalInpatientDetail),
  PERSONALITY_DISORDER(CsraReviewAnswers::personalityDisorder, CsraReviewAnswers::personalityDisorderDetail),
  WANTS_TO_HARM_OR_KILL(CsraReviewAnswers::wantsToHarmOrKill, CsraReviewAnswers::wantsToHarmOrKillDetail),
  STRANGE_OR_UNUSUAL_BEHAVIOUR(CsraReviewAnswers::strangeOrUnusualBehaviour, CsraReviewAnswers::strangeOrUnusualBehaviourDetail),
  CLINICAL_PARANOID_BEHAVIOUR(CsraReviewAnswers::clinicalParanoidBehaviour, CsraReviewAnswers::clinicalParanoidBehaviourDetail),
  FREQUENT_CELL_CHANGE_REQUESTS(CsraReviewAnswers::frequentCellChangeRequests, CsraReviewAnswers::frequentCellChangeRequestsDetail),
  OTHER_HIGH_RISK_INDICATORS(CsraReviewAnswers::otherHighRiskIndicators, CsraReviewAnswers::otherHighRiskIndicatorsDetail),
  ;

  val field: String get() = answer.name

  fun answerIn(answers: CsraReviewAnswers): Boolean? = answer.get(answers)

  fun detailIn(answers: CsraReviewAnswers): String? = detail.get(answers)

  /** Answered either way, or with details typed against it. */
  fun isAnsweredIn(answers: CsraReviewAnswers) = answerIn(answers) != null || !detailIn(answers).isNullOrBlank()
}

/**
 * The questions one type of review asks, as the designs set them out (MAPA-537). Applied when the final rating
 * is confirmed; a part-way save may leave anything unanswered.
 *
 * @property required must all be answered before the final rating is confirmed.
 * @property optional may be answered, but need not be. Only the scheduled long term high risk review has any:
 *   its pages after the statement and vulnerability page are not designed yet, so its questions there are
 *   allowed but not enforced until they are.
 * @property mandatoryHighTriggers a Yes to any of these means the only rating allowed is HIGH_GENERAL.
 * @property asksMdtQuestion whether the type asks "Has a multidisciplinary team reviewed the prisoner's cell
 *   sharing risk?", which must then be Yes for the final rating.
 */
data class CsraReviewQuestionSet(
  val required: Set<CsraReviewQuestion>,
  val optional: Set<CsraReviewQuestion> = emptySet(),
  val mandatoryHighTriggers: Set<CsraReviewQuestion> = emptySet(),
  val asksMdtQuestion: Boolean = false,
) {
  val asked: Set<CsraReviewQuestion> get() = required + optional

  companion object {
    /**
     * Where two types word a question differently but ask the same thing (repeated violence, healthcare),
     * they share its column; the stage's review reason records which wording was shown.
     */
    fun forReason(reason: CsraReviewReason): CsraReviewQuestionSet = when (reason) {
      // MAPA-524 and MAPA-525: the seven offence pages of the initial assessment, then statement and
      // vulnerability. Mandatory high is not applied, pending a product decision.
      SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW -> CsraReviewQuestionSet(
        required = setOf(
          CsraReviewQuestion.MURDER_MANSLAUGHTER,
          CsraReviewQuestion.ASSISTING_SUICIDE,
          CsraReviewQuestion.SEXUAL_ASSAULT,
          CsraReviewQuestion.REPEATED_VIOLENCE,
          CsraReviewQuestion.PREJUDICE_MOTIVATED,
          CsraReviewQuestion.ARSON,
          CsraReviewQuestion.KIDNAP_HOSTAGE,
          CsraReviewQuestion.LIKELY_TO_HARM_CELLMATE,
          CsraReviewQuestion.SIGNIFICANTLY_VULNERABLE,
        ),
        optional = setOf(
          CsraReviewQuestion.HEALTHCARE_INCREASED_RISK,
          CsraReviewQuestion.OTHER_HIGH_RISK_INDICATORS,
        ),
      )

      // MAPA-520: evidence sources, then straight to the rating. No questions.
      SHORT_TERM_HIGH_RISK_REVIEW -> CsraReviewQuestionSet(required = emptySet())

      // MAPA-529 and MAPA-531.
      NEW_OR_ADDITIONAL_INFORMATION -> CsraReviewQuestionSet(
        required = setOf(
          CsraReviewQuestion.MURDER_MANSLAUGHTER,
          CsraReviewQuestion.ASSISTING_SUICIDE,
          CsraReviewQuestion.SEXUAL_ASSAULT,
          CsraReviewQuestion.REPEATED_VIOLENCE,
          CsraReviewQuestion.PREJUDICE_MOTIVATED,
          CsraReviewQuestion.HEALTHCARE_INCREASED_RISK,
          CsraReviewQuestion.SECURE_HOSPITAL_INPATIENT,
          CsraReviewQuestion.PERSONALITY_DISORDER,
          CsraReviewQuestion.OTHER_HIGH_RISK_INDICATORS,
        ),
        mandatoryHighTriggers = setOf(
          CsraReviewQuestion.MURDER_MANSLAUGHTER,
          CsraReviewQuestion.ASSISTING_SUICIDE,
          CsraReviewQuestion.SEXUAL_ASSAULT,
        ),
        asksMdtQuestion = true,
      )

      // MAPA-535.
      RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING -> CsraReviewQuestionSet(
        required = setOf(
          CsraReviewQuestion.WANTS_TO_HARM_OR_KILL,
          CsraReviewQuestion.STRANGE_OR_UNUSUAL_BEHAVIOUR,
          CsraReviewQuestion.CLINICAL_PARANOID_BEHAVIOUR,
          CsraReviewQuestion.LIKELY_TO_HARM_CELLMATE,
          CsraReviewQuestion.SIGNIFICANTLY_VULNERABLE,
          CsraReviewQuestion.REPEATED_VIOLENCE,
          CsraReviewQuestion.FREQUENT_CELL_CHANGE_REQUESTS,
          CsraReviewQuestion.OTHER_HIGH_RISK_INDICATORS,
        ),
        asksMdtQuestion = true,
      )
    }
  }
}
