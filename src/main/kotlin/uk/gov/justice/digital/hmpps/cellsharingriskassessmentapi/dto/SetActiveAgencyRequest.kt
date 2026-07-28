package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Switch the CSRA service on or off in DPS for an agency (prison)")
data class SetActiveAgencyRequest(
  @Schema(
    description = "Whether the CSRA service should be switched on for this agency",
    example = "true",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  val active: Boolean,
)
