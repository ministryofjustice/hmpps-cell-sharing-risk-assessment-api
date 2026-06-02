package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraReviewService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
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
}
