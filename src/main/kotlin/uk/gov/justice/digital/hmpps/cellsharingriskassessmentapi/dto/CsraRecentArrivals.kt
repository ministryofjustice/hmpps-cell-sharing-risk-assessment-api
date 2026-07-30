package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.LocalDateTime

@Schema(description = "Prisoners who have arrived at a prison within the recent window and are still in the establishment")
data class CsraRecentArrivals(
  @param:Schema(
    description = "One section per calendar day in the window, most recent day first. Every day is " +
      "present even when nobody arrived on it, so the screen can show its per-day empty state",
  )
  val days: List<CsraArrivalDay>,

  @param:Schema(
    description = "The number of arrivals matching the filter across the whole window. Zero means no " +
      "arrivals matched, which is what drives the filtered empty state",
    example = "10",
  )
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

@Schema(description = "One calendar day of the window, with the arrivals on that day")
data class CsraArrivalDay(
  @param:Schema(description = "The day", example = "2026-07-09")
  val date: LocalDate,

  @param:Schema(description = "The arrivals on this day matching the filter, latest first; empty when nobody arrived")
  val arrivals: List<CsraArrivalRow>,
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

  @param:Schema(
    description = "Where the prisoner is now — a cell, or a location code such as RECP for reception. " +
      "This is their current location, not where they were put on arrival",
    example = "C-2-005",
  )
  val location: String?,
)
