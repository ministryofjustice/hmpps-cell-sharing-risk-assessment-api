package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Migrate response linking the created UUID with the Nomis id")
data class CsraMigrationResponse(
  @param:Schema(description = "The unique id of the CSRA review", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0")
  val id: UUID,

  @param:Schema(description = "The booking id of the CSRA in Nomis - the first part of its id", example = "4123456")
  val bookingId: Long,

  @param:Schema(description = "The sequence number of the CSRA in Nomis - the second part of its id", example = "4")
  val nomisSequence: Int,
)
