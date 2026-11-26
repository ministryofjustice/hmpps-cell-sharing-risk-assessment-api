package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import jakarta.validation.ValidationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraDetailedRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewAnswers
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewAnswersRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewEvidenceSourceSelection
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStartRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStarted
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.isHigh
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toAnswers
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toReviewDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraMissingAnswerDetailException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraReviewNotFoundException
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.StaleAnswersException
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Writes new (DPS) CSR reviews: a two-stage journey where a review is started as a draft, an interim rating
 * may be saved, and a final rating completes it. Each confirmed stage raises a CSRA domain event + audit.
 * The final stage's answers can also be saved part way, page by page, without a rating (MAPA-537).
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
 *    what interval this prisoner needs. Both stamp it on the review itself, and the prisoner's current date
 *    is derived from their latest completed review.
 *
 * What is shared lives in [CsraWriteSupport] and [CsraRiskCategoryValidator] rather than being duplicated.
 */
@Service
@Transactional
class CsraReviewWriteService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
  private val csraReviewService: CsraReviewService,
  private val csraCurrentRatingService: CsraCurrentRatingService,
  private val eventPublishAndAuditService: EventPublishAndAuditService,
  private val riskCategoryValidator: CsraRiskCategoryValidator,
  private val writeSupport: CsraWriteSupport,
  private val authenticationHolder: HmppsAuthenticationHolder,
  private val clock: Clock,
) {
  private val username: String get() = authenticationHolder.username ?: SYSTEM_USERNAME

  /**
   * Starts a new draft review. Rejects if a CSRA — assessment or review — is already in progress.
   *
   * With answers, the review and its FINAL stage are created in one transaction, so the evidence sources page
   * can start the review without risking an empty one being left in progress (MAPA-537).
   */
  fun start(prisonerNumber: String, request: CsraReviewStartRequest): CsraReviewStarted {
    // Rollout gate first: it is about the caller, not the record, so it must not depend on the id
    // existing or on what state the record is in.
    writeSupport.rejectIfPrisonNotActive(request.prisonId)
    writeSupport.rejectIfInProgress(prisonerNumber)
    request.answers?.let { validateEvidenceSources(it.evidenceSources) }

    val now = LocalDateTime.now(clock)
    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisonerNumber,
        // Set here as well as per-stage so the draft reaches this prison's reviews-in-progress worklist
        // before the interim stage is submitted.
        prisonId = request.prisonId,
        assessmentDate = LocalDate.now(clock),
        type = CsraType.CSRA_REVIEW,
        createdAt = now,
        createdBy = username,
      ),
    )
    request.answers?.let {
      val stage = CsraAssessmentStageEntity(csraReview = review, stage = CsraAssessmentStage.FINAL)
      stage.applySave(it, request.prisonId, now)
      csraAssessmentStageRepository.saveAndFlush(stage)
    }
    // Starting a review leaves the current rating alone, so it still describes the rating being reviewed —
    // the new review must be identified by its own id.
    return CsraReviewStarted(
      reviewId = review.id!!,
      currentRating = csraReviewService.getCurrentRating(prisonerNumber),
    )
  }

  /**
   * Saves a review's answers part way through the journey, without confirming a rating (MAPA-537). The
   * answers replace what was saved before, so an answer the user has cleared becomes null.
   *
   * Always the FINAL stage: the INTERIM stage is written only by the interim confirm, which the designs say
   * saves nothing before it. Like the assessment's part-way save, this leaves the current rating alone,
   * publishes no domain event and sends nothing to NOMIS — an unrated review is never published.
   */
  fun saveAnswers(prisonerNumber: String, reviewId: UUID, request: CsraReviewAnswersRequest): CsraReviewDto {
    // Rollout gate first: it is about the caller, not the record, so it must not depend on the id
    // existing or on what state the record is in.
    writeSupport.rejectIfPrisonNotActive(request.prisonId)
    val review = loadReview(prisonerNumber, reviewId)
    writeSupport.rejectIfNotWritable(review)
    if (review.finalResult != null) {
      throw ValidationException("Cannot save answers after the final rating has been confirmed")
    }
    validateEvidenceSources(request.answers.evidenceSources)
    rejectIfReasonChanged(review, request.answers.reviewReason)

    val now = LocalDateTime.now(clock)
    // The review row is written before the stage so that, of two concurrent writes, the second fails on the
    // review's version (a 409) rather than on the stage table's unique index (a 500).
    writeSupport.updateHeadlinePrison(review, CsraAssessmentStage.FINAL, request.prisonId)
    review.lastModifiedAt = now
    review.lastModifiedBy = username
    csraReviewRepository.saveAndFlush(review)

    val stage = csraAssessmentStageRepository.findByCsraReviewIdAndStage(reviewId, CsraAssessmentStage.FINAL)
      ?: CsraAssessmentStageEntity(csraReview = review, stage = CsraAssessmentStage.FINAL)
    if (request.version != stage.version) {
      throw StaleAnswersException(request.version, stage.version)
    }
    stage.applySave(request.answers, request.prisonId, now)
    csraAssessmentStageRepository.saveAndFlush(stage)

    return review.toReviewDto(csraAssessmentStageRepository.findAllByCsraReviewId(reviewId))
  }

  /**
   * Everything saved on a review, so the UI can resume it. Complete and closed reviews are still returned —
   * the designs' "View full review" reads them — but an archived one is hidden, as everywhere else.
   */
  @Transactional(readOnly = true)
  fun getReview(prisonerNumber: String, reviewId: UUID): CsraReviewDto {
    val review = loadReview(prisonerNumber, reviewId)
    if (review.status == CsraReviewStatus.ARCHIVED) {
      throw CsraReviewNotFoundException(reviewId.toString())
    }
    return review.toReviewDto(csraAssessmentStageRepository.findAllByCsraReviewId(reviewId))
  }

  fun submitInterim(prisonerNumber: String, reviewId: UUID, request: CsraReviewStageRequest) = submitStage(prisonerNumber, reviewId, request, CsraAssessmentStage.INTERIM)

  fun submitFinal(prisonerNumber: String, reviewId: UUID, request: CsraReviewStageRequest) = submitStage(prisonerNumber, reviewId, request, CsraAssessmentStage.FINAL)

  private fun submitStage(
    prisonerNumber: String,
    reviewId: UUID,
    request: CsraReviewStageRequest,
    stage: CsraAssessmentStage,
  ): CsraDetailedRating {
    // Rollout gate first: it is about the caller, not the record, so it must not depend on the id
    // existing or on what state the record is in.
    writeSupport.rejectIfPrisonNotActive(request.prisonId)
    val review = loadReview(prisonerNumber, reviewId)
    writeSupport.rejectIfNotWritable(review)
    // Deliberately no mandatory-high check — see the class doc.
    validateAnswerDetail(request)
    validateEvidenceSources(request.evidenceSources)
    rejectIfReasonChanged(review, request.reviewReason)
    writeSupport.rejectIfNextReviewDateNotInFuture(request.nextReviewDate, LocalDate.now(clock))
    riskCategoryValidator.validate(request.rating, request.riskTo, request.vulnerabilities)

    // The first rating on a review is a "created" event; a subsequent one (e.g. final after interim) is an
    // "amend". Decided before this submission is applied.
    val created = review.interimResult == null && review.finalResult == null
    val now = LocalDateTime.now(clock)
    val today = LocalDate.now(clock)

    // The review row is written before the stage so that, of two concurrent writes, the second fails on the
    // review's version (a 409) rather than on the stage table's unique index (a 500).
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
        // The reviewer's chosen date, kept only for a high-risk rating (R-09). A high-risk rating with no
        // date supplied sets none, rather than leaving the previous review's date to stand as this one's.
        // The prisoner's current date is derived from this by the refresh below.
        review.nextReviewDate = if (request.rating.isHigh()) request.nextReviewDate else null
      }
      // Unreachable: only this class calls submitStage, and only with INTERIM or FINAL.
      CsraAssessmentStage.PROVISIONAL -> throw IllegalStateException("A review has no provisional stage")
    }
    review.lastModifiedAt = now
    review.lastModifiedBy = username
    csraReviewRepository.saveAndFlush(review)
    upsertStage(review, stage, request, now)

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
      mdtChairName = request.mdtChairName
      applyAnswers(request.toAnswers())
      riskTo.clear()
      riskTo.addAll(request.riskTo.map { CsraAssessmentStageRiskToEntity(stage = this, category = it.category, details = it.details) })
      vulnerabilities.clear()
      vulnerabilities.addAll(request.vulnerabilities.map { CsraAssessmentStageVulnerabilityEntity(stage = this, category = it.category, details = it.details) })
    }
    csraAssessmentStageRepository.saveAndFlush(entity)
  }

  /**
   * A part-way save: the answers, and who saved them when. completedBy/completedAt are left alone, as they
   * mean "this stage's rating was confirmed", which a save is not.
   *
   * lastSavedAt is also what moves the stage's version on every save. Evidence sources live in their own table,
   * and a change to them alone does not make Hibernate increment the stage's version; the new timestamp does.
   * Without it, a save that only changed the evidence sources would leave a stale copy able to overwrite it.
   */
  private fun CsraAssessmentStageEntity.applySave(answers: CsraReviewAnswers, prisonId: String, now: LocalDateTime) {
    lastSavedBy = username
    lastSavedAt = now
    this.prisonId = prisonId
    applyAnswers(answers)
  }

  /** The question-page answers, shared by the part-way save and the confirm so the two cannot drift apart. */
  private fun CsraAssessmentStageEntity.applyAnswers(answers: CsraReviewAnswers) {
    questionSetVersion = REVIEW_QUESTION_SET_VERSION
    reviewReason = answers.reviewReason
    offenceMurderManslaughter = answers.offenceMurderManslaughter
    offenceMurderManslaughterDetail = answers.offenceMurderManslaughterDetail
    offenceAssistingSuicide = answers.offenceAssistingSuicide
    offenceAssistingSuicideDetail = answers.offenceAssistingSuicideDetail
    offenceSexualAssault = answers.offenceSexualAssault
    offenceSexualAssaultDetail = answers.offenceSexualAssaultDetail
    offenceRepeatedViolence = answers.offenceRepeatedViolence
    offenceRepeatedViolenceDetail = answers.offenceRepeatedViolenceDetail
    offencePrejudiceMotivated = answers.offencePrejudiceMotivated
    offencePrejudiceMotivatedDetail = answers.offencePrejudiceMotivatedDetail
    offenceArson = answers.offenceArson
    offenceArsonDetail = answers.offenceArsonDetail
    offenceKidnapHostage = answers.offenceKidnapHostage
    offenceKidnapHostageDetail = answers.offenceKidnapHostageDetail
    likelyToHarmCellmate = answers.likelyToHarmCellmate
    likelyToHarmCellmateDetail = answers.likelyToHarmCellmateDetail
    significantlyVulnerable = answers.significantlyVulnerable
    significantlyVulnerableDetail = answers.significantlyVulnerableDetail
    healthcareIncreasedRisk = answers.healthcareIncreasedRisk
    healthcareIncreasedRiskDetail = answers.healthcareIncreasedRiskDetail
    otherHighRiskIndicators = answers.otherHighRiskIndicators
    otherHighRiskIndicatorsDetail = answers.otherHighRiskIndicatorsDetail
    replaceEvidenceSources(this, answers.evidenceSources)
  }

  /**
   * Replaces a stage's evidence sources by source: rows still selected are updated in place, new ones added
   * and deselected ones removed.
   *
   * Not clear-and-re-add. Hibernate flushes inserts before orphan deletes, so re-adding a source the stage
   * already holds breaks the (stage_id, source) unique index — a 500. Before part-way saves that only bit an
   * amended confirm; now every save after the first, and the confirm that follows them, re-sends the same
   * sources. The assessment's offence evidence does the same for the same reason.
   */
  private fun replaceEvidenceSources(stage: CsraAssessmentStageEntity, selections: List<CsraReviewEvidenceSourceSelection>) {
    val existingBySource = stage.evidenceSources.associateBy { it.source }.toMutableMap()
    selections.forEach { selection ->
      val existing = existingBySource.remove(selection.source)
      if (existing != null) {
        existing.details = selection.details
      } else {
        stage.evidenceSources.add(selection.toEntity(stage))
      }
    }
    if (existingBySource.isNotEmpty()) {
      stage.evidenceSources.removeAll(existingBySource.values.toSet())
    }
  }

  /**
   * A source is either ticked or not, so it can appear once. Rejected here rather than left to the unique
   * index, which would surface a repeat as a 500.
   */
  private fun validateEvidenceSources(selections: List<CsraReviewEvidenceSourceSelection>) {
    val repeated = selections.groupingBy { it.source }.eachCount().filterValues { it > 1 }.keys
    if (repeated.isNotEmpty()) {
      throw ValidationException("Evidence source given more than once: ${repeated.sorted().joinToString()}")
    }
  }

  /**
   * The reason decides which questions a review asks. Once a review has an interim rating its reason is
   * settled, and a final stage under a different reason would contradict it. Before that the user can go
   * back and choose another reason, so the reason can change.
   */
  private fun rejectIfReasonChanged(review: CsraReviewEntity, reason: CsraReviewReason) {
    val interimReason = csraAssessmentStageRepository
      .findByCsraReviewIdAndStage(review.id!!, CsraAssessmentStage.INTERIM)
      ?.reviewReason
      ?: return
    if (interimReason != reason) {
      throw ValidationException("The reason for the review is $interimReason and cannot change once an interim rating has been given")
    }
  }

  private companion object {
    /** The eleven-question review set is signed off and stable for MVP. */
    private const val REVIEW_QUESTION_SET_VERSION = 1
  }
}
