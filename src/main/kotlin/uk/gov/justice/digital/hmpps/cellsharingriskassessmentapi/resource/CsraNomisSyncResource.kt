package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraSyncRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.NomisCsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraMigrationSyncService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestController
@Validated
@RequestMapping("/nomis-sync", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "NOMIS Migration & Sync", description = "Endpoints for migrating and synchronising CSRA data from NOMIS")
@PreAuthorize("hasRole('ROLE_PRISONER_CSRA__SYNC__RW')")
class CsraNomisSyncResource(
  private val csraMigrationSyncService: CsraMigrationSyncService,
) {

  @PostMapping("/migrate/{prisonerNumber}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Migrate all CSRA reviews for a prisoner from NOMIS",
    description = "Receives all of a prisoner's CSRA reviews from NOMIS and returns the assigned CSRA ids. Requires role PRISONER_CSRA__SYNC__RW.",
    responses = [
      ApiResponse(responseCode = "201", description = "CSRA reviews migrated"),
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
        description = "Missing required role. Requires the PRISONER_CSRA__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun migrate(
    @Schema(description = "The prisoner number (NOMIS offender number)", example = "A1234BC", required = true)
    @PathVariable prisonerNumber: String,
    @RequestBody @Valid reviews: List<NomisCsraReview>,
  ): List<CsraReview> = csraMigrationSyncService.migrate(prisonerNumber, reviews)

  @PostMapping("/sync/{prisonerNumber}")
  @Operation(
    summary = "Synchronise a single CSRA review from NOMIS",
    description = "Upserts a single CSRA review changed in NOMIS. Returns 201 when created, 200 when updated. Requires role PRISONER_CSRA__SYNC__RW.",
    responses = [
      ApiResponse(responseCode = "200", description = "CSRA review updated"),
      ApiResponse(responseCode = "201", description = "CSRA review created"),
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
        description = "Missing required role. Requires the PRISONER_CSRA__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun sync(
    @Schema(description = "The prisoner number (NOMIS offender number)", example = "A1234BC", required = true)
    @PathVariable prisonerNumber: String,
    @RequestBody @Valid request: CsraSyncRequest,
  ): ResponseEntity<SyncResult> = csraMigrationSyncService.sync(prisonerNumber, request).let { result ->
    ResponseEntity(result, if (result.created) HttpStatus.CREATED else HttpStatus.OK)
  }
}
