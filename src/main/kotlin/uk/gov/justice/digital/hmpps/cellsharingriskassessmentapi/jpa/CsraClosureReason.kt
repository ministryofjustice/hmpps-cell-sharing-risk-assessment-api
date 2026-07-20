package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/** Why an in-progress CSRA review was closed or archived. */
@Schema(description = "The reason an in-progress CSRA review was closed or archived")
enum class CsraClosureReason {
  /** The prisoner was admitted to another establishment (transfer or readmission) before completion. */
  NOT_COMPLETED_PRISONER_TRANSFER,
}
