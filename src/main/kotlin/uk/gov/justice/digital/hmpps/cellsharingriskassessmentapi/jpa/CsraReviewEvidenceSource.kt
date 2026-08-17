package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A source of evidence considered during a CSR review, captured as a multi-select on the review's
 * "Evidence sources" screen.
 *
 * Distinct from [CsraEvidenceSource], which answers a different question: that records where a *specific*
 * piece of offence evidence came from on an initial assessment, this records which sources the reviewer
 * consulted at all. The two overlap on DPS and PNC but are not the same list and are not interchangeable.
 *
 * The UI relabels several of these — DPS renders as "DPS/NOMIS", and [SAFETY_AND_SECURITY_FORM] as
 * "Safety and security form". [OTHER] carries free text naming the source; the rest carry none.
 *
 * The initial assessment journey does not use this at all: it has four evidence booleans on the stage,
 * which do not extend to a list this long. The two coexist rather than migrating existing assessment rows.
 */
@Schema(description = "A source of evidence considered during a cell sharing risk review")
enum class CsraReviewEvidenceSource {
  ALLOCATION_BOARD,
  ASSET_PLUS,
  DPS,
  HEALTHCARE_ASSESSMENT,
  INCENTIVES_REVIEW,
  MAPPA_REVIEW,
  INTELLIGENCE_MANAGEMENT_SERVICE,
  OASYS,
  PLACEMENT_REVIEW_FORM,
  PNC,
  RECATEGORISATION_REVIEW,
  ROTL_BOARD,
  SAFETY_AND_SECURITY_FORM,
  SAFETY_DIAGNOSTIC_TOOL,
  SECURITY_FILE,
  SENTENCE_PLAN,
  TRANSFER_CONFIRMATION_FORM,
  VIPER,
  OTHER,
}
