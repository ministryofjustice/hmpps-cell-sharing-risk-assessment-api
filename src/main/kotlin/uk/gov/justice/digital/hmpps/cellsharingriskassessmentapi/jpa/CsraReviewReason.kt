package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Why a CSR review was held — the four review types from the requirements document, captured as a
 * single-select on the review's "Reason for review" screen. Only the review journey populates this; an
 * initial assessment leaves it null.
 */
@Schema(description = "Why a cell sharing risk review was held")
enum class CsraReviewReason {
  SCHEDULED_LONG_TERM_HIGH_RISK_REVIEW,
  SHORT_TERM_HIGH_RISK_REVIEW,
  NEW_OR_ADDITIONAL_INFORMATION,
  RECENT_CHANGE_IN_BEHAVIOUR_OR_THINKING,
}
