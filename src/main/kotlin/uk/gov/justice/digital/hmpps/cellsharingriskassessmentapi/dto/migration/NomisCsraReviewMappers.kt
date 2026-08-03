package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
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
 * result is never produced by migration. PEND is a placeholder rather than a level, so it carries no
 * result — see [nomisOutcome] for how a pending review can still yield a rating from another level.
 */
fun CsraLevel?.toCsraResult(): CsraResult? = when (this) {
  CsraLevel.HI -> CsraResult.HIGH
  CsraLevel.STANDARD, CsraLevel.LOW, CsraLevel.MED -> CsraResult.STANDARD
  CsraLevel.PEND, null -> null
}

/**
 * The rating a NOMIS review resolves to, and whether it is settled.
 *
 * [settled] is *not* "went through the NOMIS approval step". Approval was a governance step rather than a
 * completion step: the level NOMIS displays is the prisoner's rating whether or not a committee signed it
 * off, and in practice almost nothing was ever signed off — no NOMIS review carries an approved level at
 * all. Treating unapproved as provisional therefore made every migrated review provisional, which is not
 * what "provisional" means here: in this service it marks an incomplete Day 1 assessment with work still
 * outstanding, and a legacy NOMIS review has nothing left to complete.
 *
 * So a NOMIS level is settled, and lands on the final result. The one exception is a review NOMIS itself
 * still holds in provisional status ([CsraStatus.P]), which is genuinely unfinished.
 */
data class NomisCsraOutcome(val result: CsraResult?, val settled: Boolean)

// Ranked strongest first. PEND is absent deliberately: it can never win a head-to-head.
private val LEVEL_PRIORITY = listOf(CsraLevel.HI, CsraLevel.STANDARD, CsraLevel.MED, CsraLevel.LOW)

/**
 * Resolves a NOMIS review to a rating, mirroring how NOMIS itself derives the CSRA shown on
 * `/api/offenders/{offenderNo}` (prison-api `OffenderAssessment.getClassificationSummary`):
 *
 *  1. an approved level that is not PEND is the final, approved rating;
 *  2. otherwise, where both the reviewer's level and the calculated level are set, the stronger of the
 *     two wins (HI > STANDARD > MED > LOW), the reviewer's level taking precedence at equal rank;
 *  3. otherwise the reviewer's level, if set — this is the case where NOMIS displays a bare "PEND";
 *  4. otherwise the calculated level, if it is not PEND;
 *  5. otherwise no rating at all.
 *
 * Whichever level wins, the outcome is settled unless NOMIS still holds the review in provisional status —
 * see [NomisCsraOutcome] for why approval does not decide this.
 */
fun NomisCsraReview.nomisOutcome(): NomisCsraOutcome {
  // A review NOMIS still holds as provisional is unfinished whatever level it carries.
  val settled = status != CsraStatus.P

  if (approvedLevel != null && approvedLevel != CsraLevel.PEND) {
    return NomisCsraOutcome(approvedLevel.toCsraResult(), settled)
  }

  val level = when {
    reviewLevel != null && calculatedLevel != null ->
      LEVEL_PRIORITY.firstNotNullOfOrNull { rank -> rank.takeIf { it == reviewLevel || it == calculatedLevel } }
    reviewLevel != null -> reviewLevel
    calculatedLevel != CsraLevel.PEND -> calculatedLevel
    else -> null
  }
  return NomisCsraOutcome(level.toCsraResult(), settled)
}

fun NomisCsraReview.toNewCsraReview(prisonerNumber: String): CsraReviewEntity {
  val outcome = nomisOutcome()
  return CsraReviewEntity(
    prisonerNumber = prisonerNumber,
    prisonId = assessmentPrisonId,
    assessmentDate = assessmentDate,
    type = assessmentType.toCsraType(),
    interimResult = outcome.interimResult(),
    interimResultDate = outcome.interimResult()?.let { assessmentDate },
    finalResult = outcome.finalResult(),
    finalResultDate = outcome.finalResult()?.let { evaluationDate ?: assessmentDate },
    // Migrated legacy reviews are historical, never in-progress (even result-less PEND rows).
    status = CsraReviewStatus.COMPLETE,
    createdAt = createdDateTime,
    createdBy = createdBy,
  )
}

/** Applies an incoming NOMIS review to an existing record (used by sync updates). */
fun CsraReviewEntity.updateFromNomis(prisonerNumber: String, review: NomisCsraReview, clock: Clock) {
  val outcome = review.nomisOutcome()
  this.prisonerNumber = prisonerNumber
  this.prisonId = review.assessmentPrisonId
  this.assessmentDate = review.assessmentDate
  this.type = review.assessmentType.toCsraType()
  this.interimResult = outcome.interimResult()
  this.interimResultDate = outcome.interimResult()?.let { review.assessmentDate }
  this.finalResult = outcome.finalResult()
  this.finalResultDate = outcome.finalResult()?.let { review.evaluationDate ?: review.assessmentDate }
  this.status = CsraReviewStatus.COMPLETE
  this.lastModifiedAt = LocalDateTime.now(clock)
  this.lastModifiedBy = review.createdBy
}

// A settled NOMIS rating is a final result. Only a review NOMIS still holds in provisional status lands on
// the interim result and reads back through the current-rating projection as provisional.
private fun NomisCsraOutcome.finalResult() = result.takeIf { settled }
private fun NomisCsraOutcome.interimResult() = result.takeIf { !settled }

/**
 * Builds the adjacent NOMIS-only record for a freshly mapped [core] review, keeping the raw NOMIS
 * values (booking id and NOMIS sequence are intentionally not stored).
 */
fun NomisCsraReview.toNomisEntity(core: CsraReviewEntity, clock: Clock): CsraReviewNomisEntity = CsraReviewNomisEntity(
  csraReview = core,
  ingestedAt = LocalDateTime.now(clock),
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
fun CsraReviewNomisEntity.updateFromNomis(review: NomisCsraReview, clock: Clock) {
  this.ingestedAt = LocalDateTime.now(clock)
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
