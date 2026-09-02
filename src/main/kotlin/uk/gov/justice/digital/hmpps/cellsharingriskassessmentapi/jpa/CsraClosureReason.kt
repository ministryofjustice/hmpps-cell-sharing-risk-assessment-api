package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/** Why an in-progress CSRA review was closed or archived. */
@Schema(description = "The reason an in-progress CSRA review was closed or archived")
enum class CsraClosureReason {
  /** The prisoner was transferred to another establishment, with no release in between, before completion. */
  NOT_COMPLETED_PRISONER_TRANSFER,

  /** The prisoner was readmitted following a period of release before completion (R-01). */
  NOT_COMPLETED_PRISONER_RELEASE,
}
