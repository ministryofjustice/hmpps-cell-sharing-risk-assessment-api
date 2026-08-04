package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import jakarta.validation.ValidationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraAssessmentInProgressException
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
  private val authenticationHolder: HmppsAuthenticationHolder,
  private val clock: Clock,
) {
  private val username: String get() = authenticationHolder.username ?: SYSTEM_USERNAME

  /** Starts a new draft assessment. Rejects if one is already in progress for the prisoner. */
  fun start(prisonerNumber: String, request: CsraAssessmentStartRequest): CsraAssessmentStarted {
    csraReviewRepository.findFirstByPrisonerNumberOrderByAssessmentDateDescIdDesc(prisonerNumber)
      // A review closed/archived on a move is no longer in progress and does not block a new one.
      ?.takeIf { it.finalResult == null && it.interimResult == null && it.status != CsraReviewStatus.ARCHIVED }
      ?.let { throw CsraAssessmentInProgressException(prisonerNumber) }

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

  private fun submitStage(
    prisonerNumber: String,
    assessmentId: UUID,
    request: CsraAssessmentStageRequest,
    stage: CsraAssessmentStage,
  ): CsraCurrentRating {
    val review = loadInitialReview(prisonerNumber, assessmentId)
    validateMandatoryHigh(request)
    validateOffenceEvidence(request)

    // The first rating on a review is a "created" event; a subsequent one (e.g. final after provisional)
    // is an "amend". Decided before this submission is applied.
    val created = review.interimResult == null && review.finalResult == null
    val now = LocalDateTime.now(clock)
    val today = LocalDate.now(clock)

    upsertStage(review, stage, request, now)
    updateHeadlinePrison(review, stage, request.prisonId)

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
  private fun validateOffenceEvidence(request: CsraAssessmentStageRequest) {
    val duplicated = request.offenceEvidence
      .groupingBy { it.offence }
      .eachCount()
      .filterValues { it > 1 }
      .keys
    if (duplicated.isNotEmpty()) {
      throw ValidationException("More than one evidence record supplied for ${duplicated.sorted().joinToString()}")
    }
  }

  /**
   * The review's headline prison is where its latest stage took place: the FINAL stage once one exists,
   * otherwise the PROVISIONAL. Amending the provisional after the final must therefore leave the review at
   * the prison the final assessment happened in.
   *
   * Keyed off the existence of a FINAL stage row rather than [CsraReviewEntity.finalResult] so it stays in
   * step by construction with `CsraReviewService.buildCurrentRating`, which derives the same thing as
   * `finalStage ?: provisionalStage` — and because the NOMIS sync path and SQL data fixes can both set
   * `finalResult` on a review that has no stage rows at all.
   *
   * The short-circuit on FINAL is load-bearing twice over: it means the row upserted moments earlier is
   * never consulted, and it is what lets an assessment that goes straight to FINAL still record a prison.
   */
  private fun updateHeadlinePrison(review: CsraReviewEntity, stage: CsraAssessmentStage, prisonId: String) {
    if (stage == CsraAssessmentStage.FINAL ||
      !csraAssessmentStageRepository.existsByCsraReviewIdAndStage(review.id!!, CsraAssessmentStage.FINAL)
    ) {
      review.prisonId = prisonId
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
