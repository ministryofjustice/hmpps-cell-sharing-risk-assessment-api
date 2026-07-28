package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A prison (agency) and whether the CSRA service is switched on for it in DPS")
data class AgencyStatus(
  @Schema(description = "Agency (prison) id", example = "MDI")
  val agencyId: String,

  @Schema(description = "Agency (prison) name", example = "Moorland (HMP & YOI)")
  val name: String,

  @Schema(description = "Whether the CSRA service is switched on for this agency in DPS", example = "true")
  val active: Boolean,
)
