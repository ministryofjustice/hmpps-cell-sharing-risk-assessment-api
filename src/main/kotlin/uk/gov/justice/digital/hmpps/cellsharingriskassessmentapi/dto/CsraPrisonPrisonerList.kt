package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

@Schema(description = "A paged list of a prison's current prisoners with their CSRA rating")
data class CsraPrisonPrisonerList(
  @param:Schema(description = "The page of prisoners matching the requested filters and sort")
  val content: List<CsraPrisonPrisoner>,

  @param:Schema(description = "The zero-based page number returned", example = "0")
  val page: Int,

  @param:Schema(description = "The page size requested", example = "25")
  val size: Int,

  @param:Schema(description = "Total number of prisoners matching the filters", example = "1015")
  val totalElements: Long,

  @param:Schema(description = "Total number of pages for the filtered result", example = "41")
  val totalPages: Int,
)

@Schema(description = "A prison's current prisoner with their current CSRA rating")
data class CsraPrisonPrisoner(
  @param:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Matthew")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Doyle")
  val lastName: String?,

  @param:Schema(description = "The current rating (final if present, otherwise interim); null means no rating", example = "STANDARD")
  val rating: CsraResult?,

  @param:Schema(description = "Whether the current rating is provisional (a Day 1 interim rating not yet finalised)", example = "false")
  val provisional: Boolean,

  @param:Schema(description = "The type of the assessment that produced the current rating; null when no rating", example = "ASSESSMENT")
  val assessmentType: CsraAssessmentTypeBucket?,

  @param:Schema(description = "The date the current rating was recorded; null when no rating", example = "2026-03-05")
  val assessedOn: LocalDate?,
)
