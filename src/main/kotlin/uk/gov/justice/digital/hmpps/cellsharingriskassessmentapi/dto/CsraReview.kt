package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "A cell sharing risk assessment review")
data class CsraReview(
  @param:Schema(description = "The unique id of the CSRA review", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val id: UUID,

  @param:Schema(description = "The prisoner number the review belongs to", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The prison the assessment took place at (may be absent for older records)", example = "LEI")
  val prisonId: String?,

  @param:Schema(description = "The date the assessment was started", example = "2025-11-22")
  val assessmentDate: LocalDate,

  @param:Schema(description = "The type of assessment", example = "RATING")
  val type: CsraType,

  @param:Schema(description = "The interim result, issued when the review cannot be completed on the first day", example = "STANDARD")
  val interimResult: CsraResult?,

  @param:Schema(description = "The date the interim result was given", example = "2025-11-22")
  val interimResultDate: LocalDate?,

  @param:Schema(description = "The final result of the review", example = "HIGH")
  val finalResult: CsraResult?,

  @param:Schema(description = "The date the final result was given", example = "2025-11-24")
  val finalResultDate: LocalDate?,

  @param:Schema(description = "The date the next review is due", example = "2026-05-22")
  val nextReviewDate: LocalDate?,

  @param:Schema(description = "When the review was created", example = "2025-11-22T12:34:56")
  val createdAt: LocalDateTime,

  @param:Schema(description = "The username that created the review", example = "NQP56Y")
  val createdBy: String,

  @param:Schema(description = "When the review was last updated", example = "2025-11-24T09:00:00")
  val lastModifiedAt: LocalDateTime?,

  @param:Schema(description = "The username that last updated the review", example = "NQP56Y")
  val lastModifiedBy: String?,
)

fun CsraReviewEntity.toDto() = CsraReview(
  id = id!!,
  prisonerNumber = prisonerNumber,
  prisonId = prisonId,
  assessmentDate = assessmentDate,
  type = type,
  interimResult = interimResult,
  interimResultDate = interimResultDate,
  finalResult = finalResult,
  finalResultDate = finalResultDate,
  nextReviewDate = nextReviewDate,
  createdAt = createdAt,
  createdBy = createdBy,
  lastModifiedAt = lastModifiedAt,
  lastModifiedBy = lastModifiedBy,
)
