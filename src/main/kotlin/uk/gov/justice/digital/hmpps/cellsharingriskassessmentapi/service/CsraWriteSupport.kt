package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraAssessmentInProgressException

/**
 * The rules both CSRA write journeys share. Extracted so the assessment and review services cannot drift
 * apart on them — where the journeys deliberately differ, they differ in their own service, not here.
 */
@Component
class CsraWriteSupport(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
) {

  /**
   * A prisoner may have only one CSRA in progress at a time, assessment or review. Deliberately
   * type-agnostic: an unrated assessment blocks starting a review just as it blocks starting a second
   * assessment, because both would then compete to set the same current rating.
   */
  fun rejectIfInProgress(prisonerNumber: String) {
    csraReviewRepository.findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber)
      // A review closed/archived on a move is no longer in progress and does not block a new one.
      ?.takeIf { it.finalResult == null && it.interimResult == null && it.status != CsraReviewStatus.ARCHIVED }
      ?.let { throw CsraAssessmentInProgressException(prisonerNumber) }
  }

  /**
   * The review's headline prison is where its latest stage took place: the FINAL stage once one exists,
   * otherwise the first stage. Amending the first stage after the final must therefore leave the review at
   * the prison the final assessment happened in.
   *
   * Keyed off the existence of a FINAL stage row rather than [CsraReviewEntity.finalResult] so it stays in
   * step by construction with `CsraReviewService.buildCurrentRating`, which derives the same thing as
   * `finalStage ?: firstStage` — and because the NOMIS sync path and SQL data fixes can both set
   * `finalResult` on a review that has no stage rows at all.
   *
   * The short-circuit on FINAL is load-bearing twice over: it means the row upserted moments earlier is
   * never consulted, and it is what lets a capture that goes straight to FINAL still record a prison.
   */
  fun updateHeadlinePrison(review: CsraReviewEntity, stage: CsraAssessmentStage, prisonId: String) {
    if (stage == CsraAssessmentStage.FINAL ||
      !csraAssessmentStageRepository.existsByCsraReviewIdAndStage(review.id!!, CsraAssessmentStage.FINAL)
    ) {
      review.prisonId = prisonId
    }
  }
}
