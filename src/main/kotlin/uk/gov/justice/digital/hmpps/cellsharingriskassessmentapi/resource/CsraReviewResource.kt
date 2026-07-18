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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentTypeBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskSortField
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraPrisonerSortField
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingFilter
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraSortDirection
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

  @GetMapping("/prisoner/{prisonerNumber}/current-rating")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns a prisoner's current CSRA rating",
    description = "The prisoner's current CSRA rating and its state (no rating / in progress / provisional / " +
      "complete), plus the supporting comments, high-risk risk-to & vulnerability detail, and next review " +
      "date. Returns a NO_RATING result rather than a 404 when the prisoner has no CSRA. Requires role " +
      "ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns the prisoner's current CSRA rating",
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
  fun getCurrentRating(
    @Parameter(description = "The prisoner number", example = "A1234BC", required = true)
    @PathVariable
    prisonerNumber: String,
  ) = csraReviewService.getCurrentRating(prisonerNumber = prisonerNumber)

  @GetMapping("/prison/{prisonId}/rating-summary")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns CSRA rating counts for a prison's current roll",
    description = "The counts behind the CSRA homepage tiles: prisoners with no rating, high risk, and " +
      "standard risk, across everyone currently in the prison (roll from prisoner-search). Each " +
      "prisoner is counted by their current rating (their latest review). Requires role ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns the prison's CSRA rating counts",
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
  fun getPrisonRatingSummary(
    @Parameter(description = "The prison id", example = "LEI", required = true)
    @PathVariable
    prisonId: String,
  ) = csraReviewService.getPrisonRatingSummary(prisonId = prisonId)

  @GetMapping("/prison/{prisonId}/prisoners")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns a prison's current prisoners with their CSRA rating",
    description = "The 'CSRA ratings for all prisoners' list: a paged, filterable, sortable table of " +
      "everyone currently in the prison (roll from prisoner-search) with their current CSRA rating " +
      "(their latest review). Prisoners with no CSRA record, or whose latest review has no saved rating, " +
      "appear with a null rating (No rating). Requires role ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns the page of prisoners",
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
  fun getPrisonPrisoners(
    @Parameter(description = "The prison id", example = "LEI", required = true)
    @PathVariable
    prisonId: String,
    @Parameter(description = "Zero-based page number", example = "0")
    @RequestParam(defaultValue = "0")
    page: Int,
    @Parameter(description = "Page size", example = "25")
    @RequestParam(defaultValue = "25")
    size: Int,
    @Parameter(description = "Column to sort by")
    @RequestParam(defaultValue = "NAME")
    sort: CsraPrisonerSortField,
    @Parameter(description = "Sort direction")
    @RequestParam(defaultValue = "ASC")
    direction: CsraSortDirection,
    @Parameter(description = "Only include prisoners with these current ratings (NO_RATING matches prisoners with no rating)")
    @RequestParam(required = false)
    ratings: List<CsraRatingFilter>?,
    @Parameter(description = "Only include prisoners whose current rating came from these assessment types")
    @RequestParam(required = false)
    assessmentTypes: List<CsraAssessmentTypeBucket>?,
    @Parameter(description = "Only include prisoners assessed on or after this date (inclusive)", example = "2026-01-01")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    fromDate: LocalDate?,
    @Parameter(description = "Only include prisoners assessed on or before this date (inclusive)", example = "2026-12-31")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    toDate: LocalDate?,
  ) = csraReviewService.getPrisonPrisoners(
    prisonId = prisonId,
    ratings = ratings,
    assessmentTypes = assessmentTypes,
    fromDate = fromDate,
    toDate = toDate,
    sort = sort,
    direction = direction,
    page = page,
    size = size,
  )

  @GetMapping("/prison/{prisonId}/high-risk-due-for-review")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
    summary = "Returns a prison's high risk prisoners due for a cell sharing risk review",
    description = "Everyone currently in the prison whose current CSRA rating is high-risk (High, " +
      "High – general, High – general (interim), or High – specific) and who has a scheduled next review " +
      "date. Each row carries the review due date, the rating type, and whether the current rating came " +
      "from an assessment or a review (for the 'Last assessed'/'Last reviewed' line). Overdue is derived " +
      "on the client from the due date. Not paginated. The response also lists the rating types present " +
      "across the establishment for the dynamic filter. Requires role ROLE_CSRA_REVIEW__R",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Returns the high risk prisoners due for review",
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
  fun getHighRiskDueForReview(
    @Parameter(description = "The prison id", example = "LEI", required = true)
    @PathVariable
    prisonId: String,
    @Parameter(description = "Only include prisoners with these high-risk rating types")
    @RequestParam(required = false)
    ratingTypes: List<CsraHighRiskType>?,
    @Parameter(description = "Only include reviews due on or after this date (inclusive)", example = "2026-01-01")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    reviewDateFrom: LocalDate?,
    @Parameter(description = "Only include reviews due on or before this date (inclusive)", example = "2026-12-31")
    @RequestParam(required = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    reviewDateTo: LocalDate?,
    @Parameter(description = "Column to sort by")
    @RequestParam(defaultValue = "REVIEW_DUE_BY")
    sort: CsraHighRiskSortField,
    @Parameter(description = "Sort direction")
    @RequestParam(defaultValue = "ASC")
    direction: CsraSortDirection,
  ) = csraReviewService.getHighRiskDueForReview(
    prisonId = prisonId,
    ratingTypes = ratingTypes,
    reviewDateFrom = reviewDateFrom,
    reviewDateTo = reviewDateTo,
    sort = sort,
    direction = direction,
  )
}
