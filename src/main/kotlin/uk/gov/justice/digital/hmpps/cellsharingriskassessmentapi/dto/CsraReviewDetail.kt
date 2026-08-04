package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraReviewDetailDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.resolvedLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A single CSRA review with everything needed to render its detail page.
 *
 * Deliberately separate from [CsraReview] rather than an extension of it: `CsraReviewEntity.toDto()` is the
 * payload published on every domain event, and a question tree there would bloat every migration/sync
 * message for consumers that never read it.
 *
 * [legacy] is present only for a review that came from NOMIS, and its presence is what identifies the
 * review as legacy — the same convention [CsraReviewSummary] already uses on the history endpoint.
 */
@Schema(description = "A CSRA review with the detail needed to render its full record")
data class CsraReviewDetail(
  @param:Schema(description = "The unique id of the CSRA review", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val id: UUID,

  @param:Schema(description = "The prisoner number the review belongs to", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The prison the assessment took place at (may be absent for older records)", example = "LEI")
  val prisonId: String?,

  @param:Schema(description = "The name of the prison the assessment took place at, falling back to the id when it is not a known prison", example = "Leeds (HMP)")
  val prisonName: String?,

  @param:Schema(description = "The date the assessment was started", example = "2025-11-22")
  val assessmentDate: LocalDate,

  @param:Schema(description = "The type of assessment", example = "CSRA_INITIAL_REVIEW")
  val type: CsraType,

  @param:Schema(description = "The interim result, issued when the review cannot be completed on the first day", example = "STANDARD")
  val interimResult: CsraResult?,

  @param:Schema(description = "The date the interim result was given", example = "2025-11-22")
  val interimResultDate: LocalDate?,

  @param:Schema(description = "The final result of the review", example = "HIGH")
  val finalResult: CsraResult?,

  @param:Schema(description = "The date the final result was given", example = "2025-11-24")
  val finalResultDate: LocalDate?,

  @param:Schema(description = "When the review was created. For a migrated review this is NOMIS's own creation timestamp.", example = "2025-11-22T12:34:56")
  val createdAt: LocalDateTime,

  @param:Schema(description = "The username that created the review. For a migrated review this is the NOMIS assessor.", example = "NQP56Y")
  val createdBy: String,

  @param:Schema(description = "When the review was last updated", example = "2025-11-24T09:00:00")
  val lastModifiedAt: LocalDateTime?,

  @param:Schema(description = "The username that last updated the review", example = "NQP56Y")
  val lastModifiedBy: String?,

  @param:Schema(description = "The legacy NOMIS detail. Present only for a migrated review; its presence is what identifies the review as legacy.")
  val legacy: CsraLegacyReviewDetail? = null,
)

/**
 * The full legacy NOMIS record behind a migrated review, including the question/answer set captured at
 * migration time.
 *
 * The field names follow what NOMIS *displays* rather than the raw column names, because the two disagree
 * in a way that has already caused confusion: NOMIS's approved CSRA is `REVIEW_SUP_LEVEL_TYPE`
 * (our `reviewLevel`), and `APPROVED_SUP_LEVEL_TYPE` (our `approvedLevel`) is never populated at all —
 * prison-api does not even map it. Hence [approvedResult] rather than a field named after `approvedLevel`.
 */
@Schema(description = "The legacy NOMIS detail for a migrated review, including its questions and answers")
data class CsraLegacyReviewDetail(
  @param:Schema(description = "The raw NOMIS level this review resolved to. Renders legacy 'Low risk'/'Medium risk' records, which both collapse to STANDARD in the review's result.", example = "LOW")
  val level: CsraLevel?,

  @param:Schema(description = "What became of the review at approval. Absent when it never went through approval, which is the common case.", required = false)
  val approvalStatus: CsraApprovalStatus?,

  @param:Schema(description = "The level NOMIS calculated from the answers given", example = "STANDARD", required = false)
  val calculatedResult: CsraLevel?,

  @param:Schema(description = "The level approved for this review — NOMIS's reviewed level, which is what it displays as the approved result", example = "STANDARD", required = false)
  val approvedResult: CsraLevel?,

  @param:Schema(description = "The assessment comment recorded in NOMIS", required = false)
  val assessmentComment: String?,

  @param:Schema(description = "The approval committee's comment. This is the comment the legacy screens label 'Approval comments'.", required = false)
  val approvalCommitteeComment: String?,

  @param:Schema(description = "The review level comment recorded in NOMIS. Not shown on the legacy screens; prefer approvalCommitteeComment.", required = false)
  val approvalComment: String?,

  @param:Schema(description = "Date the review was evaluated or approved in NOMIS", example = "2013-07-14", required = false)
  val approvalDate: LocalDate?,

  @param:Schema(description = "The committee that carried out the assessment", required = false)
  val assessmentCommittee: CsraCommittee?,

  @param:Schema(description = "The committee that approved the review", required = false)
  val approvalCommittee: CsraCommittee?,

  @param:Schema(description = "The next review date NOMIS recorded on this review. Null for records migrated before we stored it per review.", example = "2014-07-14", required = false)
  val nextReviewDate: LocalDate?,

  @param:Schema(description = "The questions asked and the answers given, in the order NOMIS supplied them. Note this is not necessarily the order the legacy NOMIS screens display, which sorts by a question sequence we do not receive.")
  val questions: List<CsraReviewQuestion>,
)

@Schema(description = "A NOMIS committee, with the wording NOMIS displays for it")
data class CsraCommittee(
  @param:Schema(description = "The NOMIS committee code", example = "REVIEW")
  val code: CsraCommitteeCode,

  @param:Schema(description = "The committee's display name", example = "Review Board")
  val name: String,
)

@Schema(description = "A question asked in a legacy NOMIS CSRA review and the answer given")
data class CsraReviewQuestion(
  @param:Schema(description = "The question as recorded in NOMIS, falling back to its code where NOMIS supplied no text", example = "Select Risk Rating")
  val question: String,

  @param:Schema(description = "The answer given. Absent where the question was not answered.", example = "Standard (No immediate risk of severe violence but risk may need to be reviewed)", required = false)
  val answer: String?,

  @param:Schema(description = "Any further answers, where more than one was given to the same question")
  val additionalAnswers: List<String> = emptyList(),
)

/** Builds the detail response, attaching the legacy block only where the review came from NOMIS. */
fun CsraReviewEntity.toDetail(nomis: CsraReviewNomisEntity?, prisonName: String?) = CsraReviewDetail(
  id = id!!,
  prisonerNumber = prisonerNumber,
  prisonId = prisonId,
  prisonName = prisonName,
  assessmentDate = assessmentDate,
  type = type,
  interimResult = interimResult,
  interimResultDate = interimResultDate,
  finalResult = finalResult,
  finalResultDate = finalResultDate,
  createdAt = createdAt,
  createdBy = createdBy,
  lastModifiedAt = lastModifiedAt,
  lastModifiedBy = lastModifiedBy,
  legacy = nomis?.toLegacyReviewDetail(),
)

fun CsraReviewNomisEntity.toLegacyReviewDetail() = CsraLegacyReviewDetail(
  level = resolvedLevel(),
  approvalStatus = approvalStatus(),
  calculatedResult = calculatedLevel,
  // NOMIS's reviewed level, NOT approvedLevel — see the class comment. approvedLevel is never populated.
  approvedResult = reviewLevel,
  assessmentComment = comment,
  approvalCommitteeComment = reviewCommitteeComment,
  approvalComment = reviewComment,
  approvalDate = evaluationDate,
  assessmentCommittee = committeeCode?.toCommittee(),
  approvalCommittee = reviewCommitteeCode?.toCommittee(),
  nextReviewDate = nextReviewDate,
  questions = reviewDetailsOrEmpty.toReviewQuestions(),
)

fun CsraCommitteeCode.toCommittee() = CsraCommittee(code = this, name = displayName)

/**
 * Flattens the stored section → question → response tree into the flat question/answer list the legacy
 * screens render, discarding the section grouping. The section codes and descriptions stay in the stored
 * JSONB, so grouping can be reintroduced later without re-migrating anything.
 *
 * Answers are filtered for nulls *before* the first is taken. Taking `responses.first().answer` would
 * otherwise yield a null [CsraReviewQuestion.answer] alongside populated
 * [CsraReviewQuestion.additionalAnswers], and a consumer that hides unanswered questions — as the existing
 * legacy screen does — would then drop real answers along with it.
 *
 * A question with no answer at all is returned rather than filtered out, matching the legacy contract and
 * leaving that presentation choice to the consumer. It should not arise in migrated data, which is built
 * from the answers actually given rather than from the questionnaire template.
 */
internal fun List<CsraReviewDetailDto>.toReviewQuestions(): List<CsraReviewQuestion> = flatMap { it.questions }
  .map { question ->
    val answers = question.responses.mapNotNull { it.answer }
    CsraReviewQuestion(
      // Falls back to the code so a row is never blank and unrenderable.
      question = question.description ?: question.code,
      answer = answers.firstOrNull(),
      additionalAnswers = answers.drop(1),
    )
  }
