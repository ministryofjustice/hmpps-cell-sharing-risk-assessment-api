package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.resolvedLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import java.time.LocalDate

/**
 * The legacy NOMIS detail behind a history row, present only for migrated reviews.
 *
 * Its presence is what tells a consumer the row came from NOMIS rather than from the new assessment or
 * review journey — the rating alone cannot say so reliably.
 *
 * Two fields exist because NOMIS's vocabulary does not map cleanly onto ours:
 *
 * - [level] is the raw NOMIS level, which is the only way to render a legacy "Low risk" or "Medium risk"
 *   row. Both collapse to `STANDARD` in the row's `rating`, deliberately — `rating` is what the service
 *   reasons about (filters, counts, the current-rating projection), and LOW/MED have not been used in
 *   NOMIS for many years, so they are display-only history.
 * - [assessmentComment] and [approvalComment] separate what the row's `reviewComment` conflates. In the
 *   NOMIS shape "review" means *approval*, so a row's single resolved comment prefers the approval one.
 *   When this block is present, prefer these fields over `reviewComment`.
 */
@Schema(description = "Legacy NOMIS detail for a migrated review. Present only on rows that came from NOMIS; when present, prefer its comments over the row's resolved reviewComment.")
data class CsraLegacyDetail(
  @param:Schema(description = "The raw NOMIS level this review resolved to. Renders legacy 'Low risk'/'Medium risk' rows, which both collapse to STANDARD in the row's rating.", example = "LOW")
  val level: CsraLevel?,

  @param:Schema(description = "The assessment comment recorded in NOMIS", required = false)
  val assessmentComment: String?,

  @param:Schema(description = "Date the assessment was recorded in NOMIS", example = "2013-07-14")
  val assessmentDate: LocalDate,

  @param:Schema(description = "What became of the review at approval. Absent when it never went through approval, which is the common case.", required = false)
  val approvalStatus: CsraApprovalStatus?,

  @param:Schema(description = "The approval comment recorded in NOMIS", required = false)
  val approvalComment: String?,

  @param:Schema(description = "The approval committee's comment recorded in NOMIS", required = false)
  val approvalCommitteeComment: String?,

  @param:Schema(description = "Date the review was evaluated or approved in NOMIS", example = "2013-07-14", required = false)
  val approvalDate: LocalDate?,
)

/** Builds the legacy block for a migrated review, given the core record's assessment date. */
fun CsraReviewNomisEntity.toLegacyDetail(assessmentDate: LocalDate) = CsraLegacyDetail(
  level = resolvedLevel(),
  assessmentComment = comment,
  assessmentDate = assessmentDate,
  approvalStatus = approvalStatus(),
  approvalComment = reviewComment,
  approvalCommitteeComment = reviewCommitteeComment,
  approvalDate = evaluationDate,
)

/**
 * Derives the approval badge, or null where the review never went through approval.
 *
 * A rejection wins outright: it is still a rejection where a level was also recorded. Note this affects
 * the badge only — the row's rating still follows NOMIS's own resolution rule, which ignores the result
 * code.
 *
 * An evaluation date on its own is deliberately not treated as evidence of approval; without an explicit
 * result code or approved level it says nothing about the outcome, and guessing would make the badge lie.
 * That is why roughly a third of legacy rows carry no badge, which is correct rather than a gap.
 */
internal fun CsraReviewNomisEntity.approvalStatus(): CsraApprovalStatus? = when {
  evaluationResultCode == CsraEvaluationResultCode.REJ -> CsraApprovalStatus.NOT_APPROVED
  evaluationResultCode == CsraEvaluationResultCode.APP -> CsraApprovalStatus.APPROVED
  approvedLevel != null && approvedLevel != CsraLevel.PEND -> CsraApprovalStatus.APPROVED
  else -> null
}
