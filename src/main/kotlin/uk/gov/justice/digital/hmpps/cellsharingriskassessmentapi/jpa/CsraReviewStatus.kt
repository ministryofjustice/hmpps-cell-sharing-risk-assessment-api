package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The lifecycle state of a CSRA review.
 *
 * - IN_PROGRESS: started and editable, no final rating yet (the new-model assessment/review journey).
 * - COMPLETE: a final rating has been recorded (also the state of migrated legacy reviews).
 * - CLOSED: an in-progress review that had a provisional/interim rating and was closed out when the
 *   prisoner moved (R-01/R-02). It is no longer in progress, but its rating still counts as current.
 * - ARCHIVED: an unrated in-progress review archived when the prisoner moved (R-01/R-02), or an unrated
 *   initial assessment cancelled by a user. Answers are retained, but the record is hidden from the
 *   service and never a current rating (R-04).
 */
@Schema(description = "The lifecycle state of a CSRA review")
enum class CsraReviewStatus {
  IN_PROGRESS,
  COMPLETE,
  CLOSED,
  ARCHIVED,
}
