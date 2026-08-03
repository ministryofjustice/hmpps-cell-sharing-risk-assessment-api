package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    // The distinction the whole ticket exists for: display keeps LOW, the service still reasons in STANDARD.
    assertThat(CsraLevel.LOW.toCsraResult()).isEqualTo(CsraResult.STANDARD)
  }

  @Test
  fun `a legacy MED review keeps its raw level`() {
    assertThat(nomis(calculatedLevel = CsraLevel.MED).toLegacyDetail(assessmentDate).level)
      .isEqualTo(CsraLevel.MED)
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
    // The common case by a distance — no NOMIS review in production carries approval data at all, so this
    // must be absent rather than reported as NOT_APPROVED.
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
  fun `an approved level that raised the rating is still simply approved`() {
    // NOMIS records no approved level on any review — zero rows across 5.1M in dev and preprod — so
    // there is no "level changed at approval" state to detect, and none is exposed.
    val detail = nomis(calculatedLevel = CsraLevel.STANDARD, approvedLevel = CsraLevel.HI)
      .toLegacyDetail(assessmentDate)

    assertThat(detail.approvalStatus).isEqualTo(CsraApprovalStatus.APPROVED)
    assertThat(detail.level).isEqualTo(CsraLevel.HI)
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
}
