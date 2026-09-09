package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraAssessmentInProgressException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraPrisonNotActiveException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotWritableException
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder

/**
 * The rules both CSRA write journeys share. Extracted so the assessment and review services cannot drift
 * apart on them — where the journeys deliberately differ, they differ in their own service, not here.
 */
@Component
class CsraWriteSupport(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
  private val activeAgenciesService: ActiveAgenciesService,
  private val authenticationHolder: HmppsAuthenticationHolder,
) {

  /**
   * CSRA is rolled out prison by prison, and a user write is only accepted for a prison it is switched on
   * for (MAPA-363). This reverses MAPA-246, which left rollout to the frontend: that held only while the
   * UI was the sole writer and gated correctly, and it turned out to be neither.
   *
   * Every stage checks, not just the starts, so switching a prison off strands work already in progress.
   * That is accepted and tested.
   *
   * [ROLLOUT_OVERRIDE_ROLE] bypasses the check. It is the escape hatch MAPA-246 was protecting - data
   * fixes, migration catch-up and support work must not be blocked by a prison's rollout state - and it
   * is deliberately outside the CSRA_REVIEW__ family and outside ROLE_PRISONER_CSRA__ADMIN, all three of
   * which the UI's client-credentials client already holds. Granting it there would disable the gate for
   * every user of the service.
   *
   * Only user HTTP writes call this. The NOMIS migrate/sync path and the movement/merge listeners must
   * keep working at any prison whatever its rollout state, so they deliberately do not - and must not:
   * [HmppsAuthenticationHolder.isOverrideRole] reads the throwing `authentication` property, so off an
   * authenticated request this would raise a 500, or poison an SQS message, rather than reject cleanly.
   *
   * The prison checked is the one on the request, so a caller sending a switched-on prison for a review
   * belonging elsewhere passes - indistinguishable from a real transfer. Closing that needs a caseload
   * check, which the API cannot do: it only ever sees a client-credentials token stamped with the acting
   * username, never the user's own roles or caseloads.
   */
  fun rejectIfPrisonNotActive(prisonId: String) {
    // Checked first: it is free, and it saves the read for the support path that most needs to be quick.
    if (authenticationHolder.isOverrideRole(ROLLOUT_OVERRIDE_ROLE)) return
    if (!activeAgenciesService.isActive(prisonId)) throw CsraPrisonNotActiveException(prisonId)
  }

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
   * A CSRA that a movement has closed or archived can no longer be edited (R-03). Until the prisoner is
   * admitted at the receiving establishment the sending prison may still work on it; at that admission
   * `CsraMovementService` ends it, and every write path has to honour that.
   *
   * The archived case is the damaging one: submitting a final rating would set the review COMPLETE and
   * make it the prisoner's current rating, resurrecting a record the service is supposed to have hidden.
   *
   * COMPLETE is deliberately still writable — amending a completed assessment is a real requirement.
   */
  fun rejectIfNotWritable(review: CsraReviewEntity) {
    if (review.status == CsraReviewStatus.CLOSED || review.status == CsraReviewStatus.ARCHIVED) {
      throw CsraReviewNotWritableException(review.id.toString(), review.status)
    }
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

  companion object {
    /** Bypasses [rejectIfPrisonNotActive]. Must never be granted to the CSRA UI's client. */
    const val ROLLOUT_OVERRIDE_ROLE = "ROLE_PRISONER_CSRA__ROLLOUT_OVERRIDE"
  }
}
