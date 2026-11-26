package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

/**
 * Which questions each reason for a review asks, written out from the designs (MAPA-520, MAPA-524, MAPA-525,
 * MAPA-529 and MAPA-535) rather than read from CsraReviewQuestionSet. A mistake in that map then fails a
 * test, instead of being copied into the test's expectations.
 */
object ReviewAnswersFixture {
  private val SCHEDULED_LONG_TERM = listOf(
    "offenceMurderManslaughter",
    "offenceAssistingSuicide",
    "offenceSexualAssault",
    "offenceRepeatedViolence",
    "offencePrejudiceMotivated",
    "offenceArson",
    "offenceKidnapHostage",
    "likelyToHarmCellmate",
    "significantlyVulnerable",
  )

  private val NEW_OR_ADDITIONAL_INFORMATION = listOf(
    "offenceMurderManslaughter",
    "offenceAssistingSuicide",
    "offenceSexualAssault",
    "offenceRepeatedViolence",
    "offencePrejudiceMotivated",
    "healthcareIncreasedRisk",
    "secureHospitalInpatient",
    "personalityDisorder",
    "otherHighRiskIndicators",
  )

  private val RECENT_CHANGE_IN_BEHAVIOUR = listOf(
    "wantsToHarmOrKill",
    "strangeOrUnusualBehaviour",
    "clinicalParanoidBehaviour",
    "likelyToHarmCellmate",
    "significantlyVulnerable",
    "offenceRepeatedViolence",
    "frequentCellChangeRequests",
    "otherHighRiskIndicators",
  )

  fun questionsFor(reason: String): List<String> = when (reason) {
    "SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW" -> SCHEDULED_LONG_TERM
    "SHORT_TERM_HIGH_RISK_REVIEW" -> emptyList()
    "NEW_OR_ADDITIONAL_INFORMATION" -> NEW_OR_ADDITIONAL_INFORMATION
    "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING" -> RECENT_CHANGE_IN_BEHAVIOUR
    else -> error("Unknown review reason $reason")
  }

  fun asksMdtQuestion(reason: String) = reason == "NEW_OR_ADDITIONAL_INFORMATION" || reason == "RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING"

  /**
   * JSON fields for a review that is complete for [reason]: every question it asks answered No and, where it
   * asks the question, mdtReviewed true. Each field starts with a comma, so this can follow any other field.
   *
   * [overrides] replace or add fields; a null value leaves the field out.
   */
  fun completeAnswerFields(reason: String, overrides: Map<String, Any?> = emptyMap()): String {
    val fields = linkedMapOf<String, Any?>()
    if (asksMdtQuestion(reason)) fields["mdtReviewed"] = true
    questionsFor(reason).forEach { fields[it] = false }
    fields.putAll(overrides)
    return fields
      .filterValues { it != null }
      .entries
      .joinToString("") { (field, value) -> """, "$field": ${if (value is String) "\"$value\"" else value}""" }
  }
}
