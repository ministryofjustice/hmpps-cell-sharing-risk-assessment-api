package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A prisoner's CSRA history: whole-history summary counts plus a filtered, paginated list of reviews")
data class CsraReviewHistory(
  @param:Schema(description = "Summary counts over the prisoner's whole CSRA history (unaffected by list filters)")
  val summary: CsraReviewHistorySummary,

  @param:Schema(description = "The page of CSRA reviews matching the requested filters")
  val content: List<CsraReviewSummary>,

  @param:Schema(description = "The zero-based page number returned", example = "0")
  val page: Int,

  @param:Schema(description = "The page size requested", example = "20")
  val size: Int,

  @param:Schema(description = "Total number of reviews matching the filters", example = "13")
  val totalElements: Long,

  @param:Schema(description = "Total number of pages for the filtered result", example = "2")
  val totalPages: Int,
)

@Schema(description = "Summary counts over a prisoner's whole CSRA history")
data class CsraReviewHistorySummary(
  @param:Schema(description = "Total number of recorded CSRAs", example = "13")
  val totalCsras: Int,

  @param:Schema(description = "Number of CSRAs with a high-risk rating (legacy High, or new general/specific)", example = "2")
  val highCount: Int,

  @param:Schema(description = "Number of CSRAs with a standard rating", example = "11")
  val standardCount: Int,

  @param:Schema(description = "The date of the earliest recorded CSRA", example = "2011-06-01")
  val firstAssessmentDate: LocalDate?,

  @param:Schema(description = "The date of the most recent recorded CSRA", example = "2025-10-11")
  val lastAssessmentDate: LocalDate?,

  @param:Schema(description = "The date of the most recent high-risk CSRA", example = "2013-07-14")
  val lastHighDate: LocalDate?,

  @param:Schema(description = "The distinct establishments the prisoner has CSRAs at, for the establishment filter (name-sorted)")
  val establishments: List<CsraEstablishment>,
)

@Schema(description = "An establishment the prisoner has a CSRA at")
data class CsraEstablishment(
  @param:Schema(description = "The prison id (the establishment filter key)", example = "LEI")
  val prisonId: String,

  @param:Schema(description = "The prison name, resolved from prison-register (falls back to the id)", example = "Leeds (HMP)")
  val prisonName: String,
)

@Schema(description = "A single CSRA review in a prisoner's history")
data class CsraReviewSummary(
  @param:Schema(description = "The unique id of the CSRA review", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val id: UUID,

  @param:Schema(description = "The type of assessment", example = "REVIEW")
  val type: CsraType,

  @param:Schema(description = "The recorded rating (final result if present, otherwise the interim result)", example = "STANDARD")
  val rating: CsraResult,

  @param:Schema(description = "The review/assessment comment, if any", example = "PNC checked. No issues found.")
  val reviewComment: String?,

  @param:Schema(description = "The prison the CSRA was recorded at", example = "LEI")
  val prisonId: String?,

  @param:Schema(description = "The date the rating was recorded", example = "2025-10-11")
  val recordedDate: LocalDate,
)
