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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraDetailedRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewAnswersRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraReviewWriteService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.util.UUID

/**
 * A sibling of [CsraAssessmentResource] rather than more endpoints on it: the review captures a different
 * question set, its own evidence sources, a reason and an MDT chair, and two of its rules deliberately
 * differ from the assessment's.
 */
@RestController
@Validated
@RequestMapping("/csra-review/prisoner/{prisonerNumber}/review", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(
  name = "CSR Review",
  description = "Creates and completes new-model cell sharing risk reviews for prisoners",
)
@PreAuthorize("hasRole('ROLE_CSRA_REVIEW__RW')")
class CsraReviewWriteResource(
  private val csraReviewWriteService: CsraReviewWriteService,
) {

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Starts a new CSR review",
    description = "Creates a draft review for the prisoner and records who started it and where. Returns " +
      "the new review's id, which identifies it for the interim and final stages, along with the " +
      "prisoner's current rating — the rating being reviewed, which starting a review leaves untouched. " +
      "The prison is required: it is what puts the draft on that prison's reviews-in-progress worklist. " +
      "Answers sent with the start are saved on the review's final stage in the same transaction, so the " +
      "first page that saves a review can start it in one call. " +
      "Returns 409 if any CSRA, assessment or review, is already in progress for the prisoner. Requires " +
      "role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The draft review was started",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraReviewStarted::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — no prison supplied, answers with no review reason, or an evidence source given more than once",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role (requires ROLE_CSRA_REVIEW__RW), or CSRA is not switched on in DPS " +
          "for the prison on the request — errorCode PrisonNotActive. ROLE_PRISONER_CSRA__ROLLOUT_OVERRIDE bypasses " +
          "the rollout check",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "A CSRA is already in progress for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun startReview(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @RequestBody @Valid
    request: CsraReviewStartRequest,
  ) = csraReviewWriteService.start(prisonerNumber, request)

  @GetMapping("/{reviewId}")
  @ResponseStatus(HttpStatus.OK)
  @PreAuthorize("hasAnyRole('ROLE_CSRA_REVIEW__R', 'ROLE_CSRA_REVIEW__RW')")
  @Operation(
    summary = "Returns a CSR review with everything saved on it",
    description = "Returns the review's status and ratings and, for each stage saved so far, its answers, " +
      "confirm-page fields and version — enough to resume an in-progress review on any page, show check " +
      "answers, or show a completed review. Requires role ROLE_CSRA_REVIEW__R or ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The review",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraReviewDto::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role (requires ROLE_CSRA_REVIEW__R or ROLE_CSRA_REVIEW__RW)",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such review for this prisoner, or the review has been archived or cancelled",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getReview(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The review id returned when the review was started", required = true)
    @PathVariable
    reviewId: UUID,
  ) = csraReviewWriteService.getReview(prisonerNumber, reviewId)

  @PutMapping("/{reviewId}/answers")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Saves a CSR review's answers part way through, without confirming a rating",
    description = "Saves the answers given so far on the review's final stage, so the review can be left and " +
      "picked up later. The answers replace whatever was saved before, so an answer that is left out is " +
      "cleared. Send the stage's version from the last read; a save based on an out-of-date version is " +
      "rejected. Does not change the prisoner's current CSRA rating, does not publish a domain event and does " +
      "not mark the stage as confirmed. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The answers were saved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraReviewDto::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — no prison or review reason supplied, an evidence source given more than " +
          "once, the review already has a final rating, or the reason differs from the one the interim rating was given under",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role (requires ROLE_CSRA_REVIEW__RW), or CSRA is not switched on in DPS " +
          "for the prison on the request — errorCode PrisonNotActive. ROLE_PRISONER_CSRA__ROLLOUT_OVERRIDE bypasses " +
          "the rollout check",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such review for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "CsraReviewNotWritable: the review has been closed or archived and can no longer be edited. " +
          "StaleAnswersVersion: the version sent is not the stage's current version. " +
          "ConcurrentUpdate: another request changed it at the same time - re-read it and try again",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun saveAnswers(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The review id returned when the review was started", required = true)
    @PathVariable
    reviewId: UUID,
    @RequestBody @Valid
    request: CsraReviewAnswersRequest,
  ) = csraReviewWriteService.saveAnswers(prisonerNumber, reviewId, request)

  @PutMapping("/{reviewId}/interim")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Submits the interim stage of a CSR review",
    description = "Records the interim answers and rating, setting the prisoner's interim CSRA result and " +
      "leaving the review in progress. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The interim stage was recorded",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraDetailedRating::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — a yes answer with no details, risk categories that do not match the rating, a next review date that is not in the future, " +
          "an evidence source given more than once, or a review reason different from the one the interim rating was given under",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role (requires ROLE_CSRA_REVIEW__RW), or CSRA is not switched on in DPS " +
          "for the prison on the request — errorCode PrisonNotActive. ROLE_PRISONER_CSRA__ROLLOUT_OVERRIDE bypasses " +
          "the rollout check",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such review for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "CsraReviewNotWritable: the review has been closed or archived by a prisoner movement and can no longer be edited. " +
          "ConcurrentUpdate: another request changed it at the same time - re-read it and try again",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun submitInterim(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The review id returned when the review was started", required = true)
    @PathVariable
    reviewId: UUID,
    @RequestBody @Valid
    request: CsraReviewStageRequest,
  ) = csraReviewWriteService.submitInterim(prisonerNumber, reviewId, request)

  @PutMapping("/{reviewId}/final")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Submits the final stage of a CSR review",
    description = "Records the final answers and rating, completing the review and setting the prisoner's " +
      "final CSRA result. For a high-risk rating the reviewer's chosen next review date is stored; for any " +
      "other rating the next review date is cleared. Requires role ROLE_CSRA_REVIEW__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The review was completed",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CsraDetailedRating::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request — a yes answer with no details, risk categories that do not match the rating, a next review date that is not in the future, " +
          "an evidence source given more than once, or a review reason different from the one the interim rating was given under",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role (requires ROLE_CSRA_REVIEW__RW), or CSRA is not switched on in DPS " +
          "for the prison on the request — errorCode PrisonNotActive. ROLE_PRISONER_CSRA__ROLLOUT_OVERRIDE bypasses " +
          "the rollout check",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such review for this prisoner",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "CsraReviewNotWritable: the review has been closed or archived by a prisoner movement and can no longer be edited. " +
          "ConcurrentUpdate: another request changed it at the same time - re-read it and try again",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun submitFinal(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "The review id returned when the review was started", required = true)
    @PathVariable
    reviewId: UUID,
    @RequestBody @Valid
    request: CsraReviewStageRequest,
  ) = csraReviewWriteService.submitFinal(prisonerNumber, reviewId, request)
}
