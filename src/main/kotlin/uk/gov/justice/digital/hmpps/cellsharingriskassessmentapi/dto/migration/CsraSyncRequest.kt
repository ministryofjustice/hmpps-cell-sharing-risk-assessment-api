package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import java.util.UUID

/**
 * Request to synchronise a single CSRA review that changed in NOMIS while NOMIS is still in use.
 *
 * When [csraReviewId] is present the review already exists in DPS and is updated; when absent it is a
 * newly created review.
 */
@Schema(description = "Request to synchronise a single CSRA review from NOMIS")
data class CsraSyncRequest(
  @param:Schema(description = "The DPS id of the review, present when it already exists in DPS", example = "8f5c2f4e-1c6b-4f5a-9c1e-2b3a4d5e6f7a", required = false)
  val csraReviewId: UUID? = null,

  @param:Schema(description = "The CSRA review in legacy NOMIS format", required = true)
  @field:Valid
  val review: NomisCsraReview,
)

@Schema(description = "Result of synchronising a single CSRA review")
data class SyncResult(
  @param:Schema(description = "The DPS id of the synchronised review", example = "8f5c2f4e-1c6b-4f5a-9c1e-2b3a4d5e6f7a", required = true)
  val csraReviewId: UUID,

  @param:Schema(description = "True if the review was created, false if it was updated", example = "true", required = true)
  val created: Boolean,
)
