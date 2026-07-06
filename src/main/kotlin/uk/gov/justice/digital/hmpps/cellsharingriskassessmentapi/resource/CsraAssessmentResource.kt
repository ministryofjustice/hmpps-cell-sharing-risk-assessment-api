package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraAssessmentService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

@RestController
@Validated
@RequestMapping("/csra-review/prisoner/{prisonerNumber}/assessment", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(
  name = "CSRA Assessment",
  description = "Creates and completes new-model initial CSRA assessments for prisoners",
)
@PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
class CsraAssessmentResource(
  private val csraAssessmentService: CsraAssessmentService,
) {

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Starts a new initial CSRA assessment",
    description = "Creates a draft assessment for the prisoner and records who started it. Returns 409 if " +
      "an assessment is already in progress. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(responseCode = "201", description = "The draft assessment was started"),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CSRA_REVIEW__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "An assessment is already in progress for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun startAssessment(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
  ) = csraAssessmentService.start(prisonerNumber)

  @PutMapping("/{assessmentId}/provisional")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Submits the provisional (Day 1) stage of an initial CSRA assessment",
    description = "Records the provisional answers and rating, setting the prisoner's interim CSRA result. " +
      "Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(responseCode = "200", description = "The provisional stage was recorded"),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, or the rating conflicts with a mandatory high-risk offence trigger",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CSRA_REVIEW__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such assessment for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun submitProvisional(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The assessment id returned when the assessment was started", required = true)
    @PathVariable
    assessmentId: UUID,
    @RequestBody @Valid
    request: CsraAssessmentStageRequest,
  ) = csraAssessmentService.submitProvisional(prisonerNumber, assessmentId, request)

  @PutMapping("/{assessmentId}/final")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Submits the final (Day 2) stage of an initial CSRA assessment",
    description = "Records the final answers and rating, setting the prisoner's final CSRA result and, for a " +
      "high-risk rating, the next review date. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(responseCode = "200", description = "The final stage was recorded"),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, or the rating conflicts with a mandatory high-risk offence trigger",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CSRA_REVIEW__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such assessment for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun submitFinal(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The assessment id returned when the assessment was started", required = true)
    @PathVariable
    assessmentId: UUID,
    @RequestBody @Valid
    request: CsraAssessmentStageRequest,
  ) = csraAssessmentService.submitFinal(prisonerNumber, assessmentId, request)
}
