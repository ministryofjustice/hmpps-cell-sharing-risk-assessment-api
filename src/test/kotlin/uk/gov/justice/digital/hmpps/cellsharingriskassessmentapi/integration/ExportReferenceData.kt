package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentTypeBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraOffence
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import java.io.File

/**
 * Writes reference-data.csv, the companion to the SchemaSpy report and data-dictionary.csv.
 *
 * Every code in this schema is a JPA string enum resolved in Kotlin - there are no reference tables in
 * the database - so the schema report alone leaves an analyst looking at a varchar with no idea which
 * values are legal. This exports those lookups, including the legacy NOMIS codes held verbatim on
 * csra_review_nomis, which are the hardest to decode from outside.
 *
 * Needs no database: the values come from the enums themselves, so the list cannot drift from the code.
 * A new enum value with no description fails the test rather than exporting a blank row.
 *
 * Excluded from normal test runs; run with `./gradlew -Pinit-db=true test` (see build.gradle.kts).
 */
class ExportReferenceData {

  @Test
  fun `exports reference data`() {
    val rows = mutableListOf<Row>()

    // ---------------------------------------------------------------- csra_review
    rows += enumRows(
      "csra_review.type",
      CsraType.entries,
      mapOf(
        CsraType.CSRA_INITIAL_REVIEW to "New DPS initial assessment - the two-stage provisional/final journey.",
        CsraType.CSRA_REVIEW to "New DPS review of an existing rating - a separate journey from the initial assessment.",
        CsraType.FULL to "Legacy NOMIS full assessment.",
        CsraType.HEALTH to "Legacy NOMIS health assessment.",
        CsraType.LOCATE to "Legacy NOMIS location assessment.",
        CsraType.RATING to "Legacy NOMIS rating assessment.",
        CsraType.RECEPTION to "Legacy NOMIS reception assessment.",
        CsraType.REVIEW to "Legacy NOMIS review.",
      ),
      notes = { if (it == CsraType.CSRA_INITIAL_REVIEW || it == CsraType.CSRA_REVIEW) "origin=DPS" else "origin=NOMIS (mapped on during migration)" },
    )

    rows += enumRows(
      "csra_review.interim_result / csra_review.final_result / csra_current_rating.rating",
      CsraResult.entries,
      mapOf(
        CsraResult.STANDARD to "Can share a cell. Legacy NOMIS LOW and MED both collapse to this value.",
        CsraResult.HIGH to "Legacy NOMIS High - cannot share a cell. NOMIS had no general/specific split, so migrated reviews use this and it is kept distinct from HIGH_GENERAL.",
        CsraResult.HIGH_GENERAL to "New DPS \"High risk - general\" - cannot share a cell with anyone.",
        CsraResult.HIGH_SPECIFIC to "New DPS \"High risk - specific\" - can share only with specific types of prisoner.",
      ),
    )

    rows += enumRows(
      "csra_review.status",
      CsraReviewStatus.entries,
      mapOf(
        CsraReviewStatus.IN_PROGRESS to "Started and editable, with no final rating yet.",
        CsraReviewStatus.COMPLETE to "A final rating has been recorded. Also the state of every migrated NOMIS review.",
        CsraReviewStatus.CLOSED to "Was in progress with a provisional or interim rating when the prisoner moved. No longer in progress, but its rating still counts as current.",
        CsraReviewStatus.ARCHIVED to "Was in progress with no rating when the prisoner moved. Retained for investigation but hidden from the service, and never a current rating.",
      ),
    )

    rows += enumRows(
      "csra_review.closure_reason",
      CsraClosureReason.entries,
      mapOf(
        CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER to "The prisoner was transferred to another establishment, with no release in between, before the review was completed.",
        CsraClosureReason.NOT_COMPLETED_PRISONER_RELEASE to "The prisoner was readmitted following a period of release before the review was completed. The rating is also reset to No rating.",
      ),
    )

    // ---------------------------------------------------------------- csra_assessment_stage
    rows += enumRows(
      "csra_assessment_stage.stage",
      CsraAssessmentStage.entries,
      mapOf(
        CsraAssessmentStage.PROVISIONAL to "The initial assessment's Day 1 rating, issued when not all information is available.",
        CsraAssessmentStage.INTERIM to "The review journey's first-stage rating, issued when a review cannot be completed in one sitting.",
        CsraAssessmentStage.FINAL to "The rating that completes either journey.",
      ),
      notes = {
        when (it) {
          CsraAssessmentStage.FINAL -> "result stored in csra_review.final_result"
          else -> "result stored in csra_review.interim_result; a review carries PROVISIONAL or INTERIM, never both"
        }
      },
    )

    rows += enumRows(
      "csra_assessment_stage.review_reason",
      CsraReviewReason.entries,
      mapOf(
        CsraReviewReason.SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW to "A scheduled long-term high risk review.",
        CsraReviewReason.SHORT_TERM_HIGH_RISK_REVIEW to "A short-term high risk review.",
        CsraReviewReason.NEW_OR_ADDITIONAL_INFORMATION to "New or additional information came to light.",
        CsraReviewReason.RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING to "A recent change in the prisoner's behaviour or thinking.",
      ),
      notes = { "review journey only; null on an initial assessment" },
    )

    // ---------------------------------------------------------------- offence evidence
    rows += enumRows(
      "csra_assessment_stage_offence_evidence.offence",
      CsraOffence.entries,
      mapOf(
        CsraOffence.MURDER_MANSLAUGHTER to "Murder, manslaughter or a life-threatening assault on another prisoner in custody.",
        CsraOffence.ASSISTING_SUICIDE to "Assisting a suicide in custody.",
        CsraOffence.SEXUAL_ASSAULT to "Sexual assault of a same-sex adult victim.",
        CsraOffence.REPEATED_VIOLENCE to "Repeated violence in custody.",
        CsraOffence.PREJUDICE_MOTIVATED to "Offending or behaviour motivated by prejudice.",
        CsraOffence.ARSON to "Arson or fire setting.",
        CsraOffence.KIDNAP_HOSTAGE to "Kidnap, hostage taking or false imprisonment.",
      ),
      notes = { "answer held as csra_assessment_stage.offence_${it.name.lowercase()}; a row here means that question was answered Yes" },
    )

    rows += enumRows(
      "csra_assessment_stage_offence_evidence.pnc / .warrant / .dps / .per / .other",
      CsraEvidenceSource.entries,
      mapOf(
        CsraEvidenceSource.PNC to "PNC - current and previous convictions.",
        CsraEvidenceSource.WARRANT to "Warrant - current charge or offence.",
        CsraEvidenceSource.DPS to "DPS - current and historical adjudications.",
        CsraEvidenceSource.PER to "PER - violent behaviours in prison, court or PECS custody.",
        CsraEvidenceSource.OTHER to "Another source, named in other_source_detail.",
      ),
      notes = { "stored as one boolean column per source, not as this code. Non-null: an unticked box means \"not this source\", not \"not answered\"" },
    )

    rows += enumRows(
      "csra_assessment_stage_evidence_source.source",
      CsraReviewEvidenceSource.entries,
      mapOf(
        CsraReviewEvidenceSource.ALLOCATION_BOARD to "Allocation board.",
        CsraReviewEvidenceSource.ASSET_PLUS to "AssetPlus.",
        CsraReviewEvidenceSource.DPS to "DPS. Shown in the interface as \"DPS/NOMIS\".",
        CsraReviewEvidenceSource.HEALTHCARE_ASSESSMENT to "Healthcare assessment.",
        CsraReviewEvidenceSource.INCENTIVES_REVIEW to "Incentives review.",
        CsraReviewEvidenceSource.MAPPA_REVIEW to "MAPPA review.",
        CsraReviewEvidenceSource.INTELLIGENCE_MANAGEMENT_SERVICE to "Intelligence Management Service.",
        CsraReviewEvidenceSource.OASYS to "OASys.",
        CsraReviewEvidenceSource.PLACEMENT_REVIEW_FORM to "Placement review form.",
        CsraReviewEvidenceSource.PNC to "PNC.",
        CsraReviewEvidenceSource.RECATEGORISATION_REVIEW to "Recategorisation review.",
        CsraReviewEvidenceSource.ROTL_BOARD to "ROTL board.",
        CsraReviewEvidenceSource.SAFETY_AND_SECURITY_FORM to "Safety and security form.",
        CsraReviewEvidenceSource.SAFETY_DIAGNOSTIC_TOOL to "Safety diagnostic tool.",
        CsraReviewEvidenceSource.SECURITY_FILE to "Security file.",
        CsraReviewEvidenceSource.SENTENCE_PLAN to "Sentence plan.",
        CsraReviewEvidenceSource.TRANSFER_CONFIRMATION_FORM to "Transfer confirmation form.",
        CsraReviewEvidenceSource.VIPER to "VIPER.",
        CsraReviewEvidenceSource.OTHER to "Another source, named in the accompanying free text.",
      ),
      notes = { "review journey only. Records which sources the reviewer consulted - a different question from where a specific piece of offence evidence came from" },
    )

    // ---------------------------------------------------------------- risk to / vulnerability
    rows += enumRows(
      "csra_assessment_stage_risk_to.category",
      CsraRiskToCategory.entries,
      mapOf(
        CsraRiskToCategory.DIFFERENT_ETHNICITY to "People of a different ethnicity.",
        CsraRiskToCategory.DIFFERENT_RELIGION to "People of a different religion.",
        CsraRiskToCategory.DISABLED to "Disabled people.",
        CsraRiskToCategory.GANG_MEMBERS to "Gang members.",
        CsraRiskToCategory.SEXUAL_MINORITY to "People of a sexual minority.",
        CsraRiskToCategory.OLD_PEOPLE to "Older people.",
        CsraRiskToCategory.SPECIFIC_PERSONS to "Specific named people - the names are in the accompanying free text.",
        CsraRiskToCategory.TRANSGENDER to "Transgender people.",
        CsraRiskToCategory.OTHER to "Another group, described in the accompanying free text.",
        CsraRiskToCategory.NONE to "No identified risk to any of these groups. A positive selection, not an absence of data.",
      ),
    )

    rows += enumRows(
      "csra_assessment_stage_vulnerability.category",
      CsraVulnerabilityCategory.entries,
      mapOf(
        CsraVulnerabilityCategory.DISABLED to "Disabled.",
        CsraVulnerabilityCategory.SEXUAL_MINORITY to "A sexual minority.",
        CsraVulnerabilityCategory.MENTAL_HEALTH to "Mental health.",
        CsraVulnerabilityCategory.NEURODIVERSITY to "Neurodiversity.",
        CsraVulnerabilityCategory.OFFENCE_TYPE to "Vulnerable because of their offence type.",
        CsraVulnerabilityCategory.OLD_PEOPLE to "Older people.",
        CsraVulnerabilityCategory.TRANSGENDER to "Transgender.",
        CsraVulnerabilityCategory.OTHER to "Another group, described in the accompanying free text.",
        CsraVulnerabilityCategory.NONE to "No identified vulnerabilities. A positive selection, not an absence of data.",
      ),
    )

    // ---------------------------------------------------------------- csra_current_rating
    rows += enumRows(
      "csra_current_rating.set_reason",
      CsraRatingSetReason.entries,
      mapOf(
        CsraRatingSetReason.RATING_SAVED to "A rating was saved - a completed or provisional assessment or review, or a migrated/synced NOMIS review.",
        CsraRatingSetReason.NO_RATING_ON_READMISSION to "Reset to \"No rating\" because the prisoner was readmitted following a period of release.",
      ),
    )

    rows += enumRows(
      "csra_current_rating.assessment_type",
      CsraAssessmentTypeBucket.entries,
      mapOf(
        CsraAssessmentTypeBucket.ASSESSMENT to "An initial assessment. Covers the new-model initial journey and the legacy rating, reception, health and locate types.",
        CsraAssessmentTypeBucket.REVIEW to "A review. Covers the new-model CSRA_REVIEW and the legacy NOMIS REVIEW type.",
      ),
    )

    // ---------------------------------------------------------------- csra_review_nomis (legacy)
    rows += enumRows(
      "csra_review_nomis.status",
      CsraStatus.entries,
      mapOf(
        CsraStatus.A to "Active.",
        CsraStatus.I to "Inactive.",
        CsraStatus.P to "Provisional. This is what makes a migrated rating provisional rather than final - it is the only case where a NOMIS review is not treated as final.",
      ),
      notes = { "raw NOMIS value, held verbatim" },
    )

    rows += enumRows(
      "csra_review_nomis.calculated_level / .review_level / .approved_level",
      CsraLevel.entries,
      mapOf(
        CsraLevel.STANDARD to "Standard.",
        CsraLevel.HI to "High.",
        CsraLevel.LOW to "Low. Unused in NOMIS for years; collapses to CsraResult.STANDARD in DPS and is display-only history.",
        CsraLevel.MED to "Medium. Unused in NOMIS for years; collapses to CsraResult.STANDARD in DPS and is display-only history.",
        CsraLevel.PEND to "Pending - no level decided.",
      ),
      notes = { "raw NOMIS value. review_level is what NOMIS displays as the approved result and what the DPS rating derives from - approved_level is null on every known row" },
    )

    rows += enumRows(
      "csra_review_nomis.committee_code / .review_committee_code",
      CsraCommitteeCode.entries,
      mapOf(
        CsraCommitteeCode.GOV to "Governor.",
        CsraCommitteeCode.MED to "Medical.",
        CsraCommitteeCode.OCA to "Observation, Classification and Allocation.",
        CsraCommitteeCode.RECP to "Reception.",
        CsraCommitteeCode.REVIEW to "Review Board.",
        CsraCommitteeCode.SECSTATE to "Secretary of State.",
        CsraCommitteeCode.SECUR to "Security.",
      ),
      notes = {
        val verified = it == CsraCommitteeCode.RECP || it == CsraCommitteeCode.REVIEW
        "NOMIS reference domain ASSESS_COMM; displayName=${it.displayName}; " +
          if (verified) "label confirmed against the live DPS profile screen" else "LABEL UNVERIFIED - best-effort expansion, check REFERENCE_CODES in preprod before relying on it"
      },
    )

    rows += enumRows(
      "csra_review_nomis.evaluation_result_code",
      CsraEvaluationResultCode.entries,
      mapOf(
        CsraEvaluationResultCode.APP to "Approved.",
        CsraEvaluationResultCode.REJ to "Rejected.",
      ),
      notes = { "raw NOMIS value, held verbatim" },
    )

    val output = File(System.getProperty("referenceDataOutput") ?: "reference-data.csv")
    output.bufferedWriter().use { writer ->
      writer.write("column_ref,code,description,notes\n")
      rows.forEach { writer.write("${it.toCsv()}\n") }
    }
    println("Wrote ${rows.size} reference data rows to ${output.absolutePath}")
  }

  /**
   * Every value of the enum, with its description. Fails rather than exporting a blank row when a value
   * has no description - a new enum value is exactly the thing a consumer would otherwise not be able to
   * decode.
   */
  private fun <T : Enum<T>> enumRows(
    columnRef: String,
    values: List<T>,
    descriptions: Map<T, String>,
    notes: (T) -> String = { "" },
  ): List<Row> {
    assertThat(values.filterNot(descriptions::containsKey))
      .describedAs("$columnRef values with no description - add one in ExportReferenceData")
      .isEmpty()

    return values.map { Row(columnRef, it.name, descriptions.getValue(it), notes(it)) }
  }

  private data class Row(
    val columnRef: String,
    val code: String,
    val description: String,
    val notes: String = "",
  ) {
    fun toCsv() = listOf(columnRef, code, description, notes).joinToString(",") { escape(it) }

    private fun escape(value: String) = "\"${value.replace("\"", "\"\"")}\""
  }
}
