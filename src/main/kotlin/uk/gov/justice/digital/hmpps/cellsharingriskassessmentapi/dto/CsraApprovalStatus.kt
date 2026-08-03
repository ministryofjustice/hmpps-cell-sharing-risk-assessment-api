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
  /** An approver confirmed the review. */
  APPROVED,

  /** An approver actively rejected the review. */
  NOT_APPROVED,
}

// There is deliberately no "level changed at approval" value, though the design shows one. Detecting it
// would need NOMIS's approved level to compare against, and no review has one — zero rows across
// 5,107,546 spanning dev and preprod. NOMIS approvals are being removed entirely (MAPA-251), so none
// will ever appear. A state that cannot occur only invites consumers to build for it.
