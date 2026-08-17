package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import jakarta.validation.ValidationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentAnswersRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraOffenceEvidence
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.isHigh
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toAssessmentDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotFoundException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.MandatoryHighRiskGeneralException
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Writes new (DPS) initial CSRA assessments: a two-stage journey where an assessment is started as a draft,
 * a provisional (Day 1) rating is submitted, and a final (Day 2) rating completes it. Each confirmed stage
 * raises a CSRA domain event + audit, and a high-risk final rating sets the prisoner's next review date.
 */
@Service
@Transactional
class CsraAssessmentService(
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

  /** Starts a new draft assessment. Rejects if a CSRA is already in progress for the prisoner. */
  fun start(prisonerNumber: String, request: CsraAssessmentStartRequest): CsraAssessmentStarted {
    writeSupport.rejectIfInProgress(prisonerNumber)

    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisonerNumber,
        // Set here as well as per-stage so the draft reaches this prison's worklist before Day 1 is submitted.
        prisonId = request.prisonId,
        assessmentDate = LocalDate.now(clock),
        type = CsraType.CSRA_INITIAL_REVIEW,
        createdAt = LocalDateTime.now(clock),
        createdBy = username,
      ),
    )
    // The current rating is left untouched by starting an assessment, so for an already-rated prisoner it
    // still describes the earlier review — the new assessment must be identified by its own id.
    return CsraAssessmentStarted(
      assessmentId = review.id!!,
      currentRating = csraReviewService.getCurrentRating(prisonerNumber),
    )
  }

  fun submitProvisional(prisonerNumber: String, assessmentId: UUID, request: CsraAssessmentStageRequest) = submitStage(prisonerNumber, assessmentId, request, CsraAssessmentStage.PROVISIONAL)

  fun submitFinal(prisonerNumber: String, assessmentId: UUID, request: CsraAssessmentStageRequest) = submitStage(prisonerNumber, assessmentId, request, CsraAssessmentStage.FINAL)

  /** Partially saves answers for one stage without confirming a rating. Does not affect the prisoner's
   * current rating, does not publish a domain event, and does not mark the stage as completed. The
   * request replaces the whole answer state for the stage so that a cleared answer results in null. */
  fun saveAnswers(prisonerNumber: String, assessmentId: UUID, stage: CsraAssessmentStage, request: CsraAssessmentAnswersRequest) {
    val review = loadInitialReview(prisonerNumber, assessmentId)
    validateOffenceEvidence(request.offenceEvidence)
    val now = LocalDateTime.now(clock)

    upsertAnswers(review, stage, request, now)
    updateHeadlinePrison(review, stage, request.prisonId)

    review.lastModifiedAt = now
    review.lastModifiedBy = username
    csraReviewRepository.saveAndFlush(review)
  }

  /** Returns the full answer state of an in-progress or completed initial assessment so the UI can
   * resume it or display the check-answers screen. */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  fun getAssessment(prisonerNumber: String, assessmentId: UUID): CsraAssessmentDto {
    val review = loadInitialReview(prisonerNumber, assessmentId)
    val stages = csraAssessmentStageRepository.findAllByCsraReviewId(assessmentId)
    return review.toAssessmentDto(stages)
  }

  private fun submitStage(
    prisonerNumber: String,
    assessmentId: UUID,
    request: CsraAssessmentStageRequest,
    stage: CsraAssessmentStage,
  ): CsraCurrentRating {
    val review = loadInitialReview(prisonerNumber, assessmentId)
    validateMandatoryHigh(request)
    validateOffenceEvidence(request.offenceEvidence)
    riskCategoryValidator.validate(request.rating, request.riskTo, request.vulnerabilities)

    // The first rating on a review is a "created" event; a subsequent one (e.g. final after provisional)
    // is an "amend". Decided before this submission is applied.
    val created = review.interimResult == null && review.finalResult == null
    val now = LocalDateTime.now(clock)
    val today = LocalDate.now(clock)

    upsertStage(review, stage, request, now)
    writeSupport.updateHeadlinePrison(review, stage, request.prisonId)

    when (stage) {
      CsraAssessmentStage.PROVISIONAL -> {
        review.interimResult = request.rating
        review.interimResultDate = today
      }
      CsraAssessmentStage.FINAL -> {
        review.finalResult = request.rating
        review.finalResultDate = today
        review.status = CsraReviewStatus.COMPLETE
        upsertNextReview(prisonerNumber, review, request.rating, today)
      }
      // Unreachable: only this class calls submitStage, and only with PROVISIONAL or FINAL. INTERIM is the
      // review journey's first stage and is written by CsraReviewWriteService.
      CsraAssessmentStage.INTERIM -> throw IllegalStateException("An initial assessment has no interim stage")
    }
    review.lastModifiedAt = now
    review.lastModifiedBy = username
    csraReviewRepository.saveAndFlush(review)

    // A saved provisional/final rating becomes the prisoner's current rating.
    csraCurrentRatingService.refreshFromReviews(prisonerNumber, username)

    eventPublishAndAuditService.publishEvent(
      eventType = if (created) CSRADomainEventType.CSRA_CREATED else CSRADomainEventType.CSRA_AMENDED,
      csraReview = review.toDto(),
      auditData = review.toDto(),
      source = InformationSource.DPS,
    )

    return csraReviewService.getCurrentRating(prisonerNumber)
  }

  private fun loadInitialReview(prisonerNumber: String, assessmentId: UUID): CsraReviewEntity {
    val review = csraReviewRepository.findByIdOrNull(assessmentId)
      ?: throw CsraReviewNotFoundException(assessmentId.toString())
    if (review.prisonerNumber != prisonerNumber || review.type != CsraType.CSRA_INITIAL_REVIEW) {
      throw CsraReviewNotFoundException(assessmentId.toString())
    }
    return review
  }

  private fun validateMandatoryHigh(request: CsraAssessmentStageRequest) {
    val mandatoryTrigger = request.offenceMurderManslaughter == true ||
      request.offenceAssistingSuicide == true ||
      request.offenceSexualAssault == true
    if (mandatoryTrigger && request.rating != CsraResult.HIGH_GENERAL) {
      throw MandatoryHighRiskGeneralException()
    }
  }

  /**
   * At most one evidence record per offence — the screen asks "where did you find evidence of X?" once per
   * offence answered Yes. Rejected here rather than left to the unique index, which would surface a
   * duplicate as a 500; silently keeping one of the pair would instead lose the assessor's text.
   */
  private fun validateOffenceEvidence(offenceEvidence: List<CsraOffenceEvidence>) {
    val duplicated = offenceEvidence
      .groupingBy { it.offence }
      .eachCount()
      .filterValues { it > 1 }
      .keys
    if (duplicated.isNotEmpty()) {
      throw ValidationException("More than one evidence record supplied for ${duplicated.sorted().joinToString()}")
    }
  }

  private fun upsertStage(
    review: CsraReviewEntity,
    stage: CsraAssessmentStage,
    request: CsraAssessmentStageRequest,
    now: LocalDateTime,
  ) {
    val entity = csraAssessmentStageRepository.findByCsraReviewIdAndStage(review.id!!, stage)
      ?: CsraAssessmentStageEntity(csraReview = review, stage = stage)
    entity.apply {
      completedBy = username
      completedAt = now
      prisonId = request.prisonId
      assessmentComment = request.assessmentComment
      dpsChecked = request.dpsChecked
      perChecked = request.perChecked
      warrantChecked = request.warrantChecked
      pncChecked = request.pncChecked
      offenceMurderManslaughter = request.offenceMurderManslaughter
      offenceAssistingSuicide = request.offenceAssistingSuicide
      offenceSexualAssault = request.offenceSexualAssault
      offenceRepeatedViolence = request.offenceRepeatedViolence
      offencePrejudiceMotivated = request.offencePrejudiceMotivated
      offenceArson = request.offenceArson
      offenceKidnapHostage = request.offenceKidnapHostage
      officerSpokeToPrisoner = request.officerSpokeToPrisoner
      likelyToHarmCellmate = request.likelyToHarmCellmate
      likelyToHarmCellmateDetail = request.likelyToHarmCellmateDetail
      significantlyVulnerable = request.significantlyVulnerable
      significantlyVulnerableDetail = request.significantlyVulnerableDetail
      causeForConcernSharing = request.causeForConcernSharing
      causeForConcernSharingDetail = request.causeForConcernSharingDetail
      otherHighRiskIndicators = request.otherHighRiskIndicators
      otherHighRiskIndicatorsDetail = request.otherHighRiskIndicatorsDetail
      seenByHealthcare = request.seenByHealthcare
      healthcareIncreasedRisk = request.healthcareIncreasedRisk
      healthcareIncreasedRiskDetail = request.healthcareIncreasedRiskDetail
      offenceEvidence.clear()
      offenceEvidence.addAll(request.offenceEvidence.map { it.toEntity(this) })
      riskTo.clear()
      riskTo.addAll(request.riskTo.map { CsraAssessmentStageRiskToEntity(stage = this, category = it.category, details = it.details) })
      vulnerabilities.clear()
      vulnerabilities.addAll(request.vulnerabilities.map { CsraAssessmentStageVulnerabilityEntity(stage = this, category = it.category, details = it.details) })
    }
    csraAssessmentStageRepository.saveAndFlush(entity)
  }

  /**
   * Saves the answer fields for one stage without setting completedBy/completedAt or assessmentComment
   * (those are set only when the stage is confirmed via [upsertStage]). Sets lastSavedBy/lastSavedAt to
   * track partial saves across sessions.
   */
  private fun upsertAnswers(
    review: CsraReviewEntity,
    stage: CsraAssessmentStage,
    request: CsraAssessmentAnswersRequest,
    now: LocalDateTime,
  ) {
    val entity = csraAssessmentStageRepository.findByCsraReviewIdAndStage(review.id!!, stage)
      ?: CsraAssessmentStageEntity(csraReview = review, stage = stage)
    entity.apply {
      lastSavedBy = username
      lastSavedAt = now
      prisonId = request.prisonId
      dpsChecked = request.dpsChecked
      perChecked = request.perChecked
      warrantChecked = request.warrantChecked
      pncChecked = request.pncChecked
      offenceMurderManslaughter = request.offenceMurderManslaughter
      offenceAssistingSuicide = request.offenceAssistingSuicide
      offenceSexualAssault = request.offenceSexualAssault
      offenceRepeatedViolence = request.offenceRepeatedViolence
      offencePrejudiceMotivated = request.offencePrejudiceMotivated
      offenceArson = request.offenceArson
      offenceKidnapHostage = request.offenceKidnapHostage
      officerSpokeToPrisoner = request.officerSpokeToPrisoner
      likelyToHarmCellmate = request.likelyToHarmCellmate
      likelyToHarmCellmateDetail = request.likelyToHarmCellmateDetail
      significantlyVulnerable = request.significantlyVulnerable
      significantlyVulnerableDetail = request.significantlyVulnerableDetail
      causeForConcernSharing = request.causeForConcernSharing
      causeForConcernSharingDetail = request.causeForConcernSharingDetail
      otherHighRiskIndicators = request.otherHighRiskIndicators
      otherHighRiskIndicatorsDetail = request.otherHighRiskIndicatorsDetail
      seenByHealthcare = request.seenByHealthcare
      healthcareIncreasedRisk = request.healthcareIncreasedRisk
      healthcareIncreasedRiskDetail = request.healthcareIncreasedRiskDetail
      offenceEvidence.clear()
      offenceEvidence.addAll(request.offenceEvidence.map { it.toEntity(this) })
      riskTo.clear()
      riskTo.addAll(request.riskTo.map { CsraAssessmentStageRiskToEntity(stage = this, category = it.category, details = it.details) })
      vulnerabilities.clear()
      vulnerabilities.addAll(request.vulnerabilities.map { CsraAssessmentStageVulnerabilityEntity(stage = this, category = it.category, details = it.details) })
    }
    csraAssessmentStageRepository.saveAndFlush(entity)
  }

  /** Sets the prisoner's single next review date: 12 months on for a high-risk final rating, else cleared. */
  private fun upsertNextReview(prisonerNumber: String, review: CsraReviewEntity, rating: CsraResult, finalDate: LocalDate) {
    val nextReviewDate = if (rating.isHigh()) finalDate.plusMonths(12) else null
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
}
