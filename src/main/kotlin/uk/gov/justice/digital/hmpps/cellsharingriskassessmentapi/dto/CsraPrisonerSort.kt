package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "The column to sort the prison prisoner list by")
enum class CsraPrisonerSortField {
  NAME,
  ASSESSMENT_TYPE,
  ASSESSED_ON,
  RATING,
}

@Schema(description = "Sort direction")
enum class CsraSortDirection {
  ASC,
  DESC,
}
