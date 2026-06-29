package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.Clock
import java.time.LocalDateTime

/**
 * Maps the legacy NOMIS CSRA shape onto the core [CsraReviewEntity].
 *
 * Core data common to the new and legacy journeys is mapped onto [CsraReviewEntity]; the remaining
 * NOMIS-only data (raw levels, committee/approval data, scores, comments and the question/answer
 * detail) is preserved verbatim on the adjacent [CsraReviewNomisEntity].
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

/**
 * Builds the adjacent NOMIS-only record for a freshly mapped [core] review, keeping the raw NOMIS
 * values (booking id and NOMIS sequence are intentionally not stored).
 */
fun NomisCsraReview.toNomisEntity(core: CsraReviewEntity): CsraReviewNomisEntity = CsraReviewNomisEntity(
  csraReview = core,
  score = score,
  status = status,
  calculatedLevel = calculatedLevel,
  reviewLevel = reviewLevel,
  approvedLevel = approvedLevel,
  committeeCode = committeeCode,
  reviewCommitteeCode = reviewCommitteeCode,
  evaluationDate = evaluationDate,
  evaluationResultCode = evaluationResultCode,
  comment = comment,
  reviewComment = reviewComment,
  reviewCommitteeComment = reviewCommitteeComment,
  placementPrisonId = placementPrisonId,
  reviewPlacementPrisonId = reviewPlacementPrisonId,
  reviewDetails = reviewDetails,
)

/** Applies an incoming NOMIS review to an existing adjacent NOMIS-only record (sync updates). */
fun CsraReviewNomisEntity.updateFromNomis(review: NomisCsraReview) {
  this.score = review.score
  this.status = review.status
  this.calculatedLevel = review.calculatedLevel
  this.reviewLevel = review.reviewLevel
  this.approvedLevel = review.approvedLevel
  this.committeeCode = review.committeeCode
  this.reviewCommitteeCode = review.reviewCommitteeCode
  this.evaluationDate = review.evaluationDate
  this.evaluationResultCode = review.evaluationResultCode
  this.comment = review.comment
  this.reviewComment = review.reviewComment
  this.reviewCommitteeComment = review.reviewCommitteeComment
  this.placementPrisonId = review.placementPrisonId
  this.reviewPlacementPrisonId = review.reviewPlacementPrisonId
  this.reviewDetails = review.reviewDetails
}
