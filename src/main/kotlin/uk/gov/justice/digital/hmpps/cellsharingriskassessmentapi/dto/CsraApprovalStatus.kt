package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What became of a legacy NOMIS review at the approval step.
 *
 * Only ever set for migrated NOMIS reviews — the new service has no approval step. It is **nullable**:
 * NOMIS only recorded approval data when someone used the `OIDCAPPR` screen, and about a third of migrated
 * reviews have none. A review with no approval data has no status here, and the UI shows no badge — it must
 * not be reported as [NOT_APPROVED], which would assert that a committee actively declined it.
 */
@Schema(description = "What became of a legacy NOMIS review at the approval step. Absent when the review never went through approval.")
enum class CsraApprovalStatus {
  /** An approver confirmed the review. */
  APPROVED,

  /** An approver actively rejected the review. */
  NOT_APPROVED,
}

// There is deliberately no "level changed at approval" value, though the design shows one: it is a
// refinement of APPROVED rather than a separate outcome, so it is carried on the legacy detail as
// levelChangedAtApproval. See that function for the rule.
