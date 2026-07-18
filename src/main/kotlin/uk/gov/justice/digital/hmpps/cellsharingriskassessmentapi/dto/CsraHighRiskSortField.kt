package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "The column to sort the high-risk due-for-review list by")
enum class CsraHighRiskSortField {
  REVIEW_DUE_BY,
  NAME,
}
