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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentAnswersRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
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
class CsraAssessmentResource(
  private val csraAssessmentService: CsraAssessmentService,
) {

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
  @Operation(
    summary = "Starts a new initial CSRA assessment",
    description = "Creates a draft assessment for the prisoner and records who started it and where. Returns " +
      "the new assessment's id, which identifies it for the provisional and final stages, along with the " +
      "prisoner's current rating — which is unchanged by starting an assessment and so may refer to an " +
      "earlier review. The prison is required: it is what puts the draft on that prison's assessments-in-" +
      "progress worklist. Returns 409 if an assessment is already in progress. Requires role " +
      "ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The draft assessment was started",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraAssessmentStarted::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — no prison supplied",
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
    @RequestBody @Valid
    request: CsraAssessmentStartRequest,
  ) = csraAssessmentService.start(prisonerNumber, request)

  @PutMapping("/{assessmentId}/provisional")
  @ResponseStatus(HttpStatus.OK)
  @PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
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
  @PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
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

  @GetMapping("/{assessmentId}")
  @ResponseStatus(HttpStatus.OK)
  @PreAuthorize("hasRole('ROLE_CSRA_REVIEW__R')")
  @Operation(
    summary = "Returns the full answer set for an initial CSRA assessment",
    description = "Returns the review's status and, for each stage that has been written to, the full " +
      "answer set, enough to pre-fill every question page and the check-answers screen so an in-progress " +
      "assessment can be resumed. The UI derives section-completion state from the answers. Requires role " +
      "ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The assessment was found",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraAssessmentDto::class))],
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
  fun getAssessment(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The assessment id returned when the assessment was started", required = true)
    @PathVariable
    assessmentId: UUID,
  ) = csraAssessmentService.getAssessment(prisonerNumber, assessmentId)

  @PutMapping("/{assessmentId}/stage/{stage}/answers")
  @ResponseStatus(HttpStatus.OK)
  @PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
  @Operation(
    summary = "Partially saves answers for one stage without confirming a rating",
    description = "Saves the current answer state for a stage without requiring a rating or assessment " +
      "comment. Replaces the whole answer set for the stage so that a previously given answer can be " +
      "cleared. Does not affect the prisoner's current CSRA rating, does not publish a domain event, and " +
      "does not mark the stage as confirmed. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The answers were saved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraAssessmentDto::class))]
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — no prison supplied, or duplicate offence evidence entries",
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
  fun saveAnswers(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The assessment id returned when the assessment was started", required = true)
    @PathVariable
    assessmentId: UUID,
    @Parameter(description = "The stage to save answers for", required = true)
    @PathVariable
    stage: CsraAssessmentStage,
    @RequestBody @Valid
    request: CsraAssessmentAnswersRequest,
  ) = csraAssessmentService.saveAnswers(prisonerNumber, assessmentId, stage, request)
}
