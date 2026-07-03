package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraReviewService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.time.LocalDate
import java.util.UUID

@RestController
@Validated
@RequestMapping("/csra-review", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(
  name = "CSRA Review",
  description = "Returns CSRA reviews for prisoners",
)
@PreAuthorize("hasRole('ROLE_CSRA_REVIEW__R')")
class CsraReviewResource(
  private val csraReviewService: CsraReviewService,
) : EventBase() {

  @GetMapping("/{id}")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns CSRA review for this ID",
    description = "Requires role ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns CSRA Review Information",
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CSRA_REVIEW__R role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "Data not found",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getCsraReview(
    @Schema(description = "CSRA Review ID", example = "de91dfa7-821f-4552-a427-bf2f32eafeb0", required = true)
    @PathVariable
    id: UUID,
  ) = csraReviewService.getCsraReviewById(
    id = id,
  )
    ?: throw CsraReviewNotFoundException(id.toString())

  @GetMapping("/prisoner/{prisonerNumber}/history")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns a prisoner's CSRA history",
    description = "A filtered, paginated list of a prisoner's recorded CSRAs (both new-model and legacy " +
      "NOMIS-migrated), plus whole-history summary counts. Returns an empty list and zeroed summary when " +
      "the prisoner has no CSRAs. Requires role ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns the prisoner's CSRA history",
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CSRA_REVIEW__R role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getCsraHistory(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
    @Parameter(description = "Zero-based page number", example = "0")
    @RequestParam(defaultValue = "0")
    page: Int,
    @Parameter(description = "Page size", example = "20")
    @RequestParam(defaultValue = "20")
    size: Int,
    @Parameter(description = "Only include CSRAs recorded on or after this date (inclusive)", example = "2024-01-01")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    fromDate: LocalDate?,
    @Parameter(description = "Only include CSRAs recorded on or before this date (inclusive)", example = "2025-12-31")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    toDate: LocalDate?,
    @Parameter(description = "Only include CSRAs recorded at these prisons", example = "LEI")
    @RequestParam(required = false)
    establishments: List<String>?,
    @Parameter(description = "Only include CSRAs in these rating buckets")
    @RequestParam(required = false)
    ratings: List<CsraRatingBucket>?,
  ) = csraReviewService.getCsraHistory(
    prisonerNumber = prisonerNumber,
    page = page,
    size = size,
    fromDate = fromDate,
    toDate = toDate,
    establishments = establishments,
    ratings = ratings,
  )
}
