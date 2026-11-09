package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.toCsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime

class CsraLegacyDetailTest {

  private val assessmentDate = LocalDate.parse("2013-07-14")

  private fun core() = CsraReviewEntity(
    prisonerNumber = "A1234BC",
    assessmentDate = assessmentDate,
    type = CsraType.RATING,
    createdAt = LocalDateTime.parse("2013-07-14T09:00:00"),
    createdBy = "NQP56Y",
  )

  private fun nomis(
    calculatedLevel: CsraLevel? = null,
    overrideLevel: CsraLevel? = null,
    reviewLevel: CsraLevel? = null,
    approvedLevel: CsraLevel? = null,
    evaluationResultCode: CsraEvaluationResultCode? = null,
    evaluationDate: LocalDate? = null,
    comment: String? = null,
    reviewComment: String? = null,
    reviewCommitteeComment: String? = null,
  ) = CsraReviewNomisEntity(
    csraReview = core(),
    status = CsraStatus.A,
    calculatedLevel = calculatedLevel,
    overrideLevel = overrideLevel,
    reviewLevel = reviewLevel,
    approvedLevel = approvedLevel,
    evaluationResultCode = evaluationResultCode,
    evaluationDate = evaluationDate,
    comment = comment,
    reviewComment = reviewComment,
    reviewCommitteeComment = reviewCommitteeComment,
  )

  @Test
  fun `a legacy LOW review keeps its raw level even though it rates as standard`() {
    val detail = nomis(calculatedLevel = CsraLevel.LOW).toLegacyDetail(assessmentDate)

    assertThat(detail.level).isEqualTo(CsraLevel.LOW)
    assertThat(detail.calculatedLevel).isEqualTo(CsraLevel.LOW)
    // The distinction the whole ticket exists for: display keeps LOW, the service still reasons in STANDARD.
    assertThat(CsraLevel.LOW.toCsraResult()).isEqualTo(CsraResult.STANDARD)
  }

  @Test
  fun `a legacy MED review keeps its raw level`() {
    val detail = nomis(calculatedLevel = CsraLevel.MED).toLegacyDetail(assessmentDate)

    assertThat(detail.level).isEqualTo(CsraLevel.MED)
    assertThat(detail.calculatedLevel).isEqualTo(CsraLevel.MED)
  }

  @Test
  fun `maps the comments and dates NOMIS records separately`() {
    val detail = nomis(
      calculatedLevel = CsraLevel.HI,
      comment = "assessment comment",
      reviewComment = "approval comment",
      reviewCommitteeComment = "committee comment",
      evaluationDate = LocalDate.parse("2013-07-20"),
    ).toLegacyDetail(assessmentDate)

    assertThat(detail.assessmentComment).isEqualTo("assessment comment")
    assertThat(detail.assessmentDate).isEqualTo(assessmentDate)
    assertThat(detail.approvalComment).isEqualTo("approval comment")
    assertThat(detail.approvalCommitteeComment).isEqualTo("committee comment")
    assertThat(detail.approvalDate).isEqualTo(LocalDate.parse("2013-07-20"))
  }

  @Test
  fun `a review that never went through approval carries no approval status`() {
    // About a third of migrated reviews have no approval data, so this must be absent rather than reported
    // as NOT_APPROVED.
    val detail = nomis(calculatedLevel = CsraLevel.HI).toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isNull()
    assertThat(detail.approvalDate).isNull()
  }

  @Test
  fun `an approved level matching the level already held is approved`() {
    val detail = nomis(calculatedLevel = CsraLevel.HI, approvedLevel = CsraLevel.HI)
      .toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isEqualTo(CsraApprovalStatus.APPROVED)
  }

  @Test
  fun `an approved level is shown as approved but does not change the level`() {
    // NOMIS records no approved level on any review — zero rows across 5.1M in dev and preprod — and
    // prison-api never reads it, so it feeds the approval badge only, never the level.
    val detail = nomis(calculatedLevel = CsraLevel.STANDARD, approvedLevel = CsraLevel.HI)
      .toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isEqualTo(CsraApprovalStatus.APPROVED)
    assertThat(detail.level).isEqualTo(CsraLevel.STANDARD)
    assertThat(detail.calculatedLevel).isEqualTo(CsraLevel.STANDARD)
  }

  @Test
  fun `a review level that lowered the calculated and override levels is the level shown`() {
    val detail = nomis(calculatedLevel = CsraLevel.HI, overrideLevel = CsraLevel.HI, reviewLevel = CsraLevel.STANDARD)
      .toLegacyDetail(assessmentDate)

    assertThat(detail.level).isEqualTo(CsraLevel.STANDARD)
    assertThat(detail.calculatedLevel).isEqualTo(CsraLevel.HI)
    assertThat(detail.overrideLevel).isEqualTo(CsraLevel.HI)
  }

  @Test
  fun `an APP result code with no approved level is approved`() {
    val detail = nomis(calculatedLevel = CsraLevel.HI, evaluationResultCode = CsraEvaluationResultCode.APP)
      .toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isEqualTo(CsraApprovalStatus.APPROVED)
  }

  @Test
  fun `a rejection wins even where an approved level was also recorded`() {
    val detail = nomis(
      calculatedLevel = CsraLevel.STANDARD,
      approvedLevel = CsraLevel.HI,
      evaluationResultCode = CsraEvaluationResultCode.REJ,
    ).toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isEqualTo(CsraApprovalStatus.NOT_APPROVED)
  }

  @Test
  fun `an evaluation date alone says nothing about the outcome`() {
    val detail = nomis(calculatedLevel = CsraLevel.HI, evaluationDate = LocalDate.parse("2013-07-20"))
      .toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isNull()
    assertThat(detail.approvalDate).isEqualTo(LocalDate.parse("2013-07-20"))
  }

  @ParameterizedTest(name = "calculated {0}, override {1}, approved {2} gives {3}")
  @CsvSource(
    nullValues = ["null"],
    value = [
      // The approver changed what the assessor put forward.
      "PEND, STANDARD, HI, true",
      "HI, STANDARD, HI, true",
      "STANDARD, null, HI, true",
      "STANDARD, HI, STANDARD, true",
      // The approver agreed with what the assessor put forward.
      "STANDARD, HI, HI, false",
      "PEND, HI, HI, false",
      "HI, null, HI, false",
      // Nothing was put forward, so there is nothing to have changed.
      "PEND, null, HI, false",
      "null, null, HI, false",
      "PEND, PEND, HI, false",
      // A PEND override is not a level anyone chose, so the calculated level stands.
      "STANDARD, PEND, HI, true",
      // No approved level, so nothing was changed at approval.
      "STANDARD, HI, null, false",
      "STANDARD, HI, PEND, false",
    ],
  )
  fun `compares the approved level with the level the assessor put forward`(
    calculated: CsraLevel?,
    override: CsraLevel?,
    approved: CsraLevel?,
    expected: Boolean,
  ) {
    val nomis = nomis(
      calculatedLevel = calculated,
      overrideLevel = override,
      reviewLevel = approved,
      evaluationResultCode = CsraEvaluationResultCode.APP,
    )

    assertThat(nomis.toLegacyDetail(assessmentDate).levelChangedAtApproval).isEqualTo(expected)
  }

  @Test
  fun `a rejected review is never a level changed at approval`() {
    val detail = nomis(
      calculatedLevel = CsraLevel.STANDARD,
      reviewLevel = CsraLevel.HI,
      evaluationResultCode = CsraEvaluationResultCode.REJ,
    ).toLegacyDetail(assessmentDate)

    assertThat(detail.levelChangedAtApproval).isFalse()
  }

  @Test
  fun `a review that never went through approval is never a level changed at approval`() {
    val detail = nomis(calculatedLevel = CsraLevel.STANDARD, reviewLevel = CsraLevel.HI).toLegacyDetail(assessmentDate)

    assertThat(detail.levelChangedAtApproval).isFalse()
  }
}
