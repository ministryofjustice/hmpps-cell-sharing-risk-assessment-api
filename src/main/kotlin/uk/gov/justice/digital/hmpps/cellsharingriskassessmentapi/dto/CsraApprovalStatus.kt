package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What became of a legacy NOMIS review at the approval step.
 *
 * Only ever set for migrated NOMIS reviews — the new service has no approval step. It is **nullable**, and
 * absent is the common case rather than an edge one: NOMIS only recorded approval data when someone used
 * the `OIDCAPPR` screen, which in practice was almost never. A review with no approval data has no status
 * here, and the UI shows no badge — it must not be reported as [NOT_APPROVED], which would assert that a
 * committee actively declined it.
 */
@Schema(description = "What became of a legacy NOMIS review at the approval step. Absent when the review never went through approval, which is the common case.")
enum class CsraApprovalStatus {
  /** An approver confirmed the review, at the level it already carried. */
  APPROVED,

  /** An approver confirmed the review but at a different level from the one it carried beforehand. */
  LEVEL_CHANGED_AT_APPROVAL,

  /** An approver actively rejected the review. */
  NOT_APPROVED,
}
