package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A stage of a new (DPS) CSRA capture. Both journeys are a two-stage capture belonging to a single review
 * record, and both finish on FINAL, but their first stage is named differently because policy and the
 * screens themselves treat the two as distinct:
 *
 * - PROVISIONAL: the initial assessment's Day 1 rating, issued when not all information is available.
 * - INTERIM: the review's first-stage rating, issued when a review cannot be completed in one sitting.
 * - FINAL: the rating that completes either journey. Its result is stored in
 *   [CsraReviewEntity.finalResult].
 *
 * PROVISIONAL and INTERIM both store their result in [CsraReviewEntity.interimResult] and leave the review
 * IN_PROGRESS; the distinction is what the user was told it was called, which is why they are not
 * collapsed into one value. A given review carries one or the other, never both.
 */
@Schema(description = "A stage of a new CSRA assessment or review")
enum class CsraAssessmentStage {
  PROVISIONAL,
  INTERIM,
  FINAL,
}
