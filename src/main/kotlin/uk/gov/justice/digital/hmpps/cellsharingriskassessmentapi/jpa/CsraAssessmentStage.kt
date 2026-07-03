package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A stage of the new (DPS) initial CSRA assessment. The assessment is a two-stage capture belonging to
 * a single review record:
 *
 * - PROVISIONAL: the Day 1 rating, issued when not all information is available. Its result is stored in
 *   [CsraReviewEntity.interimResult] and remains the prisoner's current CSRA value until the final stage.
 * - FINAL: the Day 2 rating that completes the assessment. Its result is stored in
 *   [CsraReviewEntity.finalResult].
 */
@Schema(description = "A stage of the new initial CSRA assessment")
enum class CsraAssessmentStage {
  PROVISIONAL,
  FINAL,
}
