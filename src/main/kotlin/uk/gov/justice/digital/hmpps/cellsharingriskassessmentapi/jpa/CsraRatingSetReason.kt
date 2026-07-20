package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

/** Why a prisoner's current CSRA rating was last set. */
enum class CsraRatingSetReason {
  /** A rating was saved (a completed/provisional assessment or review, or a migrated/synced NOMIS review). */
  RATING_SAVED,

  /** Reset to "No rating" because the prisoner was readmitted following a period of release (R-01). */
  NO_RATING_ON_READMISSION,
}
