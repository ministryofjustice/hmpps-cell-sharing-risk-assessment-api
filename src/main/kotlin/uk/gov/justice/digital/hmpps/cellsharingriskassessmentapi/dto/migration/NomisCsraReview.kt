package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A single CSRA review as held in the legacy NOMIS system.
 *
 * This is the legacy shape received from hmpps-prisoner-from-nomis-migration during migration and
 * synchronisation. Field names must match the producer's `CsraReviewDto` exactly so the JSON binds.
 * It is deliberately distinct from the (richer) DPS model that this service will own going forward.
 */
@Schema(description = "A single CSRA review in the legacy NOMIS format")
data class NomisCsraReview(
  @param:Schema(description = "Prison where the assessment took place (NOMIS agencyId). Not currently populated by NOMIS.", example = "LEI", required = false)
  val assessmentPrisonId: String? = null,

  @param:Schema(description = "Date the CSRA was created", example = "2025-11-22", required = true)
  val assessmentDate: LocalDate,

  @param:Schema(description = "CSRA assessment type", example = "CSR", required = true)
  val assessmentType: CsraAssessmentType,

  @param:Schema(description = "The calculated CSRA level", example = "STANDARD", required = false)
  val calculatedLevel: CsraLevel? = null,

  @param:Schema(description = "Score", example = "1000", required = true)
  val score: BigDecimal,

  @param:Schema(description = "Status: active, inactive or provisional", example = "A", required = true)
  val status: CsraStatus,

  @param:Schema(description = "The assessment committee code", example = "REVIEW", required = false)
  val committeeCode: CsraCommitteeCode? = null,

  @param:Schema(description = "Next review date", example = "2026-05-22", required = false)
  val nextReviewDate: LocalDate? = null,

  @param:Schema(description = "Comment text", required = false)
  val comment: String? = null,

  @param:Schema(description = "A prison to be transferred to", example = "LEI", required = false)
  val placementPrisonId: String? = null,

  @param:Schema(description = "Timestamp for when the CSRA was created", example = "2025-12-06T12:34:56", required = true)
  val createdDateTime: LocalDateTime,

  @param:Schema(description = "The user who created the CSRA", example = "NQP56Y", required = true)
  @field:NotBlank
  val createdBy: String,

  @param:Schema(description = "The review CSRA level", example = "STANDARD", required = false)
  val reviewLevel: CsraLevel? = null,

  @param:Schema(description = "The approved CSRA level", example = "STANDARD", required = false)
  val approvedLevel: CsraLevel? = null,

  @param:Schema(description = "Evaluation or approval date", example = "2025-12-08", required = false)
  val evaluationDate: LocalDate? = null,

  @param:Schema(description = "Approved or rejected indicator", example = "APP", required = false)
  val evaluationResultCode: CsraEvaluationResultCode? = null,

  @param:Schema(description = "The review/approval committee code", example = "REVIEW", required = false)
  val reviewCommitteeCode: CsraCommitteeCode? = null,

  @param:Schema(description = "Approval committee comment text", required = false)
  val reviewCommitteeComment: String? = null,

  @param:Schema(description = "A prison to be transferred to (from the review/approval)", example = "LEI", required = false)
  val reviewPlacementPrisonId: String? = null,

  @param:Schema(description = "Review/approval comment text", required = false)
  val reviewComment: String? = null,

  @param:Schema(description = "Question and answer details by section", required = true)
  @field:Valid
  val reviewDetails: List<CsraReviewDetailDto> = emptyList(),
)

@Schema(description = "A section of question and answer details within a CSRA review")
data class CsraReviewDetailDto(
  @param:Schema(description = "Section code", required = true)
  @field:NotBlank
  val code: String,

  @param:Schema(description = "Section description", required = false)
  val description: String? = null,

  @param:Schema(description = "Questions within this section", required = true)
  @field:Valid
  val questions: List<CsraQuestionDto> = emptyList(),
)

@Schema(description = "A question and its responses within a CSRA review section")
data class CsraQuestionDto(
  @param:Schema(description = "Question code", required = true)
  @field:NotBlank
  val code: String,

  @param:Schema(description = "Question description", required = false)
  val description: String? = null,

  @param:Schema(description = "Responses to this question", required = true)
  @field:Valid
  val responses: List<CsraResponseDto> = emptyList(),
)

@Schema(description = "A single response to a CSRA question")
data class CsraResponseDto(
  @param:Schema(description = "Response code", required = true)
  @field:NotBlank
  val code: String,

  @param:Schema(description = "Free-text answer", required = false)
  val answer: String? = null,

  @param:Schema(description = "Comment text", required = false)
  val comment: String? = null,
)

@Schema(description = "CSRA assessment type as configured in the NOMIS ASSESSMENTS table")
enum class CsraAssessmentType {
  CSRF,
  CSRH,
  CSRDO,
  CSR,
  CSR1,
  CSRREV,
}

@Schema(description = "A CSRA level (used for calculated, review and approved levels)")
enum class CsraLevel {
  STANDARD,
  PEND,
  LOW,
  MED,
  HI,
}

@Schema(description = "CSRA status: I = inactive, A = active, P = provisional")
enum class CsraStatus {
  I,
  A,
  P,
}

@Schema(description = "Assessment committee code (NOMIS reference domain ASSESS_COMM)")
enum class CsraCommitteeCode {
  GOV,
  MED,
  OCA,
  RECP,
  REVIEW,
  SECSTATE,
  SECUR,
}

@Schema(description = "Approved or rejected indicator")
enum class CsraEvaluationResultCode {
  APP,
  REJ,
}
