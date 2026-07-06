package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

/**
 * Codes that can be used by api clients to uniquely discriminate between error types,
 * instead of relying on non-constant text descriptions of HTTP status codes.
 *
 * NB: Once defined, the values must not be changed
 */
enum class ErrorCode(val errorCode: Int) {
  CsraReviewNotFound(101),
  MandatoryHighRiskGeneral(102),
  AssessmentInProgress(103),
}
