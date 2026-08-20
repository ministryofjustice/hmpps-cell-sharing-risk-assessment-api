package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.isHigh
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraMissingAnswerDetailException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraNextReviewDateInvalidException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotFoundException
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Writes new (DPS) CSR reviews: a two-stage journey where a review is started as a draft, an interim rating
 * may be saved, and a final rating completes it. Each confirmed stage raises a CSRA domain event + audit.
 *
 * **The review journey is not the assessment journey with different questions.** Reading
 * [CsraAssessmentService] first makes it tempting to reuse its rules wholesale; two of them deliberately
 * differ, and both differences are load-bearing:
 *
 * 1. **Mandatory-high offences are advisory here, not enforced.** An assessment rejects any rating other
 *    than HIGH_GENERAL when a mandatory trigger offence is answered Yes. A review revisits an existing
 *    rating with more context — often precisely to conclude that a historic trigger no longer warrants
 *    high risk — so the trigger informs the reviewer rather than dictating the outcome.
 * 2. **The next review date is chosen by the reviewer, not computed.** An assessment sets it to the final
 *    rating date plus twelve months. A review takes the date from the request, because the reviewer knows
 *    what interval this prisoner needs. Both write the same single row per prisoner, so last write wins.
 *
 * What is shared lives in [CsraWriteSupport] and [CsraRiskCategoryValidator] rather than being duplicated.
 */
@Service
@Transactional
class CsraReviewWriteService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val csraReviewService: CsraReviewService,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val riskCategoryValidator: CsraRiskCategoryValidator,
  private val writeSupport: CsraWriteSupport,
  private val authenticationHolder: HmppsAuthenticationHolder,
  private val clock: Clock,
) {
  private val username: String get() = authenticationHolder.username ?: SYSTEM_USERNAME

  /** Starts a new draft review. Rejects if a CSRA — assessment or review — is already in progress. */
  fun start(prisonerNumber: String, request: CsraReviewStartRequest): CsraReviewStarted {
    writeSupport.rejectIfInProgress(prisonerNumber)

    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisonerNumber,
        // Set here as well as per-stage so the draft reaches this prison's reviews-in-progress worklist
        // before the interim stage is submitted.
        prisonId = request.prisonId,
        assessmentDate = LocalDate.now(clock),
        type = CsraType.CSRA_REVIEW,
        createdAt = LocalDateTime.now(clock),
        createdBy = username,
      ),
    )
    // Starting a review leaves the current rating alone, so it still describes the rating being reviewed —
    // the new review must be identified by its own id.
    return CsraReviewStarted(
      reviewId = review.id!!,
      currentRating = csraReviewService.getCurrentRating(prisonerNumber),
    )
  }

  fun submitInterim(prisonerNumber: String, reviewId: UUID, request: CsraReviewStageRequest) = submitStage(prisonerNumber, reviewId, request, CsraAssessmentStage.INTERIM)

  fun submitFinal(prisonerNumber: String, reviewId: UUID, request: CsraReviewStageRequest) = submitStage(prisonerNumber, reviewId, request, CsraAssessmentStage.FINAL)

  private fun submitStage(
    prisonerNumber: String,
    reviewId: UUID,
    request: CsraReviewStageRequest,
    stage: CsraAssessmentStage,
  ): CsraCurrentRating {
    val review = loadReview(prisonerNumber, reviewId)
    // Deliberately no mandatory-high check — see the class doc.
    validateAnswerDetail(request)
    validateNextReviewDate(request)
    riskCategoryValidator.validate(request.rating, request.riskTo, request.vulnerabilities)

    // The first rating on a review is a "created" event; a subsequent one (e.g. final after interim) is an
    // "amend". Decided before this submission is applied.
    val created = review.interimResult == null && review.finalResult == null
    val now = LocalDateTime.now(clock)
    val today = LocalDate.now(clock)

    upsertStage(review, stage, request, now)
    writeSupport.updateHeadlinePrison(review, stage, request.prisonId)

    when (stage) {
      CsraAssessmentStage.INTERIM -> {
        review.interimResult = request.rating
        review.interimResultDate = today
      }
      CsraAssessmentStage.FINAL -> {
        review.finalResult = request.rating
        review.finalResultDate = today
        review.status = CsraReviewStatus.COMPLETE
        upsertNextReview(prisonerNumber, review, request.rating, request.nextReviewDate)
      }
      // Unreachable: only this class calls submitStage, and only with INTERIM or FINAL.
      CsraAssessmentStage.PROVISIONAL -> throw IllegalStateException("A review has no provisional stage")
    }
    review.lastModifiedAt = now
    review.lastModifiedBy = username
    csraReviewRepository.saveAndFlush(review)

    // A saved interim/final rating becomes the prisoner's current rating.
    csraCurrentRatingService.refreshFromReviews(prisonerNumber, username)

    eventPublishAndAuditService.publishEvent(
      eventType = if (created) CSRADomainEventType.CSRA_CREATED else CSRADomainEventType.CSRA_AMENDED,
      csraReview = review.toDto(),
      auditData = review.toDto(),
      source = InformationSource.DPS,
    )

    return csraReviewService.getCurrentRating(prisonerNumber)
  }

  private fun loadReview(prisonerNumber: String, reviewId: UUID): CsraReviewEntity {
    val review = csraReviewRepository.findByIdOrNull(reviewId)
      ?: throw CsraReviewNotFoundException(reviewId.toString())
    // A mismatched prisoner or an assessment id is a 404, not a 403: the caller has no business knowing
    // the id exists.
    if (review.prisonerNumber != prisonerNumber || review.type != CsraType.CSRA_REVIEW) {
      throw CsraReviewNotFoundException(reviewId.toString())
    }
    return review
  }

  /**
   * Every review question reveals a "provide details" box on Yes, and the answer is not complete without
   * it. A No stores whatever detail is sent and never requires one — a UI that later decides to capture
   * context on a No then needs no API change, and nothing the reviewer typed is silently discarded.
   *
   * [CsraReviewEvidenceSource.OTHER] is held to the same rule: selecting it without naming the source records
   * that evidence was used while losing what it was.
   */
  private fun validateAnswerDetail(request: CsraReviewStageRequest) {
    val missing = buildList {
      fun require(question: String, answer: Boolean?, detail: String?) {
        if (answer == true && detail.isNullOrBlank()) add(question)
      }
      require("offenceMurderManslaughter", request.offenceMurderManslaughter, request.offenceMurderManslaughterDetail)
      require("offenceAssistingSuicide", request.offenceAssistingSuicide, request.offenceAssistingSuicideDetail)
      require("offenceSexualAssault", request.offenceSexualAssault, request.offenceSexualAssaultDetail)
      require("offenceRepeatedViolence", request.offenceRepeatedViolence, request.offenceRepeatedViolenceDetail)
      require("offencePrejudiceMotivated", request.offencePrejudiceMotivated, request.offencePrejudiceMotivatedDetail)
      require("offenceArson", request.offenceArson, request.offenceArsonDetail)
      require("offenceKidnapHostage", request.offenceKidnapHostage, request.offenceKidnapHostageDetail)
      require("likelyToHarmCellmate", request.likelyToHarmCellmate, request.likelyToHarmCellmateDetail)
      require("significantlyVulnerable", request.significantlyVulnerable, request.significantlyVulnerableDetail)
      require("healthcareIncreasedRisk", request.healthcareIncreasedRisk, request.healthcareIncreasedRiskDetail)
      require("otherHighRiskIndicators", request.otherHighRiskIndicators, request.otherHighRiskIndicatorsDetail)

      if (request.evidenceSources.any { it.source == CsraReviewEvidenceSource.OTHER && it.details.isNullOrBlank() }) {
        add("evidenceSources.OTHER")
      }
    }
    if (missing.isNotEmpty()) {
      throw CsraMissingAnswerDetailException(missing)
    }
  }

  /**
   * The reviewer picks the next review date, so unlike the computed assessment date it can be wrong. Only
   * the obvious error is rejected — a date already past sets up a review that is overdue the moment it is
   * saved. No upper bound: policy runs high-risk reviews on a twelve-month cycle, but the API has not been
   * told that is a hard limit, and inventing one would silently block a legitimate longer interval.
   */
  private fun validateNextReviewDate(request: CsraReviewStageRequest) {
    val date = request.nextReviewDate ?: return
    if (!date.isAfter(LocalDate.now(clock))) {
      throw CsraNextReviewDateInvalidException("nextReviewDate must be in the future, but was $date")
    }
  }

  private fun upsertStage(
    review: CsraReviewEntity,
    stage: CsraAssessmentStage,
    request: CsraReviewStageRequest,
    now: LocalDateTime,
  ) {
    val entity = csraAssessmentStageRepository.findByCsraReviewIdAndStage(review.id!!, stage)
      ?: CsraAssessmentStageEntity(csraReview = review, stage = stage, version = 1)
    entity.apply {
      completedBy = username
      completedAt = now
      prisonId = request.prisonId
      // The designs label this "Review comment" and the assessment's "Assessment comment", but it is the
      // same concept and the same column; the wording is the UI's business.
      assessmentComment = request.reviewComment
      questionSetVersion = REVIEW_QUESTION_SET_VERSION
      reviewReason = request.reviewReason
      mdtChairName = request.mdtChairName
      offenceMurderManslaughter = request.offenceMurderManslaughter
      offenceMurderManslaughterDetail = request.offenceMurderManslaughterDetail
      offenceAssistingSuicide = request.offenceAssistingSuicide
      offenceAssistingSuicideDetail = request.offenceAssistingSuicideDetail
      offenceSexualAssault = request.offenceSexualAssault
      offenceSexualAssaultDetail = request.offenceSexualAssaultDetail
      offenceRepeatedViolence = request.offenceRepeatedViolence
      offenceRepeatedViolenceDetail = request.offenceRepeatedViolenceDetail
      offencePrejudiceMotivated = request.offencePrejudiceMotivated
      offencePrejudiceMotivatedDetail = request.offencePrejudiceMotivatedDetail
      offenceArson = request.offenceArson
      offenceArsonDetail = request.offenceArsonDetail
      offenceKidnapHostage = request.offenceKidnapHostage
      offenceKidnapHostageDetail = request.offenceKidnapHostageDetail
      likelyToHarmCellmate = request.likelyToHarmCellmate
      likelyToHarmCellmateDetail = request.likelyToHarmCellmateDetail
      significantlyVulnerable = request.significantlyVulnerable
      significantlyVulnerableDetail = request.significantlyVulnerableDetail
      healthcareIncreasedRisk = request.healthcareIncreasedRisk
      healthcareIncreasedRiskDetail = request.healthcareIncreasedRiskDetail
      otherHighRiskIndicators = request.otherHighRiskIndicators
      otherHighRiskIndicatorsDetail = request.otherHighRiskIndicatorsDetail
      evidenceSources.clear()
      evidenceSources.addAll(request.evidenceSources.map { it.toEntity(this) })
      riskTo.clear()
      riskTo.addAll(request.riskTo.map { CsraAssessmentStageRiskToEntity(stage = this, category = it.category, details = it.details) })
      vulnerabilities.clear()
      vulnerabilities.addAll(request.vulnerabilities.map { CsraAssessmentStageVulnerabilityEntity(stage = this, category = it.category, details = it.details) })
    }
    csraAssessmentStageRepository.saveAndFlush(entity)
  }

  /**
   * Sets the prisoner's single next review date from the reviewer's choice — cleared when the rating is not
   * high risk, because only high-risk ratings carry a review date (R-09).
   *
   * A high-risk rating with no date supplied also clears it. That is deliberate: the alternative, leaving
   * the previous review's date in place, would silently attribute an old date to this review through
   * [CsraNextReviewEntity.setByReviewId].
   */
  private fun upsertNextReview(prisonerNumber: String, review: CsraReviewEntity, rating: CsraResult, chosenDate: LocalDate?) {
    val nextReviewDate = if (rating.isHigh()) chosenDate else null
    val existing = csraNextReviewRepository.findByPrisonerNumber(prisonerNumber)
    val entity = existing?.apply {
      this.nextReviewDate = nextReviewDate
      this.setByReviewId = review.id!!
      this.updatedAt = LocalDateTime.now(clock)
      this.updatedBy = username
    } ?: CsraNextReviewEntity(
      prisonerNumber = prisonerNumber,
      nextReviewDate = nextReviewDate,
      setByReviewId = review.id!!,
      updatedAt = LocalDateTime.now(clock),
      updatedBy = username,
    )
    csraNextReviewRepository.saveAndFlush(entity)
  }

  private companion object {
    /** The eleven-question review set is signed off and stable for MVP. */
    private const val REVIEW_QUESTION_SET_VERSION = 1
  }
}
