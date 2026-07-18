package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A prison's in-progress CSRA initial assessments, split by whether a provisional rating has been entered")
data class CsraAssessmentsInProgress(
  @param:Schema(description = "Assessments started but with no rating entered yet")
  val assessmentStarted: List<CsraAssessmentStartedRow>,

  @param:Schema(description = "Assessments with a provisional rating entered, awaiting a final rating")
  val provisionalRatingEntered: List<CsraProvisionalRatingRow>,
)

@Schema(description = "An in-progress assessment that has been started but has no rating entered")
data class CsraAssessmentStartedRow(
  @param:Schema(description = "The CSRA review id (for continuing the assessment)", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val reviewId: UUID,

  @param:Schema(description = "The prisoner number", example = "A9354JF")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Simon")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Kettleby")
  val lastName: String?,

  @param:Schema(description = "The date the assessment was started", example = "2026-07-06")
  val startedOn: LocalDate,

  @param:Schema(description = "The username of who started the assessment", example = "JBLOGGS")
  val startedBy: String,
)

@Schema(description = "An in-progress assessment with a provisional rating entered")
data class CsraProvisionalRatingRow(
  @param:Schema(description = "The CSRA review id (for continuing the assessment)", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val reviewId: UUID,

  @param:Schema(description = "The prisoner number", example = "A5197BD")
  val prisonerNumber: String,

  @param:Schema(description = "The prisoner's first name", example = "Daniel")
  val firstName: String?,

  @param:Schema(description = "The prisoner's last name", example = "Havers")
  val lastName: String?,

  @param:Schema(description = "The date the provisional rating was given", example = "2026-07-06")
  val assessedOn: LocalDate,

  @param:Schema(description = "The username of who gave the provisional rating", example = "MSTANLEY")
  val assessedBy: String,

  @param:Schema(description = "The provisional rating", example = "HIGH_SPECIFIC")
  val rating: CsraResult,
)
