package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.Clock
import java.time.LocalDateTime

/**
 * Maps the legacy NOMIS CSRA shape onto the core [CsraReviewEntity].
 *
 * Only core data is mapped; legacy-only NOMIS data (review detail, committee/approval data, scores)
 * is intentionally dropped and will be persisted to a separate legacy table in a later step.
 */

fun CsraAssessmentType.toCsraType(): CsraType = when (this) {
  CsraAssessmentType.CSRF -> CsraType.FULL
  CsraAssessmentType.CSRH -> CsraType.HEALTH
  CsraAssessmentType.CSRDO -> CsraType.LOCATE
  CsraAssessmentType.CSR -> CsraType.RATING
  CsraAssessmentType.CSR1 -> CsraType.RECEPTION
  CsraAssessmentType.CSRREV -> CsraType.REVIEW
}

/**
 * Maps a legacy NOMIS level to the new result model. NOMIS has no "high specific" level, so that
 * result is never produced by migration. PEND (pending) maps to no result.
 */
fun CsraLevel?.toCsraResult(): CsraResult? = when (this) {
  CsraLevel.HI -> CsraResult.HIGH
  CsraLevel.STANDARD, CsraLevel.LOW, CsraLevel.MED -> CsraResult.STANDARD
  CsraLevel.PEND, null -> null
}

// The final NOMIS outcome, preferring the approved level, then the reviewer's, then the calculated.
private fun NomisCsraReview.finalCsraResult(): CsraResult? = (approvedLevel ?: reviewLevel ?: calculatedLevel).toCsraResult()

fun NomisCsraReview.toNewCsraReview(prisonerNumber: String): CsraReviewEntity {
  val result = finalCsraResult()
  return CsraReviewEntity(
    prisonerNumber = prisonerNumber,
    prisonId = assessmentPrisonId,
    assessmentDate = assessmentDate,
    type = assessmentType.toCsraType(),
    finalResult = result,
    finalResultDate = result?.let { evaluationDate ?: assessmentDate },
    nextReviewDate = nextReviewDate,
    createdAt = createdDateTime,
    createdBy = createdBy,
  )
}

/** Applies an incoming NOMIS review to an existing record (used by sync updates). */
fun CsraReviewEntity.updateFromNomis(prisonerNumber: String, review: NomisCsraReview, clock: Clock) {
  val result = review.finalCsraResult()
  this.prisonerNumber = prisonerNumber
  this.prisonId = review.assessmentPrisonId
  this.assessmentDate = review.assessmentDate
  this.type = review.assessmentType.toCsraType()
  this.finalResult = result
  this.finalResultDate = result?.let { review.evaluationDate ?: review.assessmentDate }
  this.nextReviewDate = review.nextReviewDate
  this.lastModifiedAt = LocalDateTime.now(clock)
  this.lastModifiedBy = review.createdBy
}
