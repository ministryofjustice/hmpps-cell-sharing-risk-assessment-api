package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.LocalDateTime

@Schema(description = "Prisoners who have arrived at a prison within the recent window and are still in the establishment")
data class CsraRecentArrivals(
  @param:Schema(description = "The arrivals matching the filter, most recent first")
  val arrivals: List<CsraArrivalRow>,

  @param:Schema(description = "The number of arrivals matching the filter", example = "10")
  val totalResults: Int,

  @param:Schema(
    description = "Count of arrivals per type across the whole window (unaffected by the filter), for the " +
      "filter checkbox counts; every type is always present (zero when none)",
  )
  val arrivalTypeCounts: Map<CsraArrivalType, Int>,

  @param:Schema(description = "The first day of the window (inclusive)", example = "2026-07-07")
  val fromDate: LocalDate,

  @param:Schema(description = "The last day of the window (inclusive, today)", example = "2026-07-09")
  val toDate: LocalDate,
)

@Schema(description = "A prisoner who arrived at the prison")
data class CsraArrivalRow(
  @param:Schema(description = "The prisoner number", example = "A5197BD")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Daniel")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Havers")
  val lastName: String?,

  @param:Schema(description = "The prisoner's date of birth", example = "1972-02-03")
  val dateOfBirth: LocalDate?,

  @param:Schema(description = "The type of arrival", example = "NEW_ADMISSION")
  val arrivalType: CsraArrivalType,

  @param:Schema(description = "When the prisoner arrived", example = "2026-07-09T14:03:00")
  val arrivedAt: LocalDateTime,

  @param:Schema(description = "The prisoner's location on arrival (reception or a cell)", example = "Reception")
  val location: String?,
)
