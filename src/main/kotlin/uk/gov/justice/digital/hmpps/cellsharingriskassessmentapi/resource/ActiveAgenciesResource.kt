package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.AgencyStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.SetActiveAgencyRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.ActiveAgenciesService
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestController
@Validated
@RequestMapping("/active-agencies", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(
  name = "Active agencies",
  description = "Controls which prisons have the CSRA service switched on in DPS",
)
@PreAuthorize("hasRole('ROLE_PRISONER_CSRA__ADMIN')")
class ActiveAgenciesResource(
  private val activeAgenciesService: ActiveAgenciesService,
  private val authenticationHolder: HmppsAuthenticationHolder,
) {

  @GetMapping
  @Operation(
    summary = "List the agencies the CSRA service is switched on for",
    description = "Returns the ids of the prisons using CSRA in DPS. The same list is published " +
      "unauthenticated on /info as activeAgencies. Requires role PRISONER_CSRA__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "The active agency ids"),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the PRISONER_CSRA__ADMIN role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getActiveAgencies(): List<String> = activeAgenciesService.getActiveAgencies()

  @GetMapping("/all")
  @Operation(
    summary = "List every prison with whether the CSRA service is switched on",
    description = "Returns every operational prison, plus any already switched on, for the rollout " +
      "admin screen. Requires role PRISONER_CSRA__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "The prisons and their current state"),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the PRISONER_CSRA__ADMIN role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getAllAgencies(): List<AgencyStatus> = activeAgenciesService.getAllAgencies()

  @PutMapping("/{agencyId}")
  @Operation(
    summary = "Switch the CSRA service on or off for an agency",
    description = "Switches the CSRA service on or off in DPS for a prison. Idempotent. " +
      "Requires role PRISONER_CSRA__ADMIN.",
    responses = [
      ApiResponse(responseCode = "200", description = "The agency's new state"),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the PRISONER_CSRA__ADMIN role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun setActiveAgency(
    @Schema(description = "The agency (prison) id", example = "MDI", required = true)
    @PathVariable agencyId: String,
    @RequestBody @Valid request: SetActiveAgencyRequest,
  ): AgencyStatus = activeAgenciesService.setActive(agencyId, request.active, currentUsername())

  private fun currentUsername(): String = authenticationHolder.username ?: SYSTEM_USERNAME
}
