package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraArrivalRow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraArrivalType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentStartedRow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentTypeBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentsInProgress
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraEstablishment
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskDueForReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskReviewRow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskSortField
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraHighRiskType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraPrisonPrisoner
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraPrisonPrisonerList
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraPrisonRatingSummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraPrisonerSortField
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraProvisionalRatingRow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingFilter
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRecentArrivals
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewHistory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewHistorySummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewInProgressRow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewSummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewsInProgress
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRiskToDetail
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraSortDirection
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraVulnerabilityDetail
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.HIGH_RESULTS
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.isHigh
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toAssessmentBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toResults
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewSpecifications
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraSummaryRow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CsraReviewService(
  private val csraReviewRepository: CsraReviewRepository,
  private val csraReviewNomisRepository: CsraReviewNomisRepository,
  private val csraAssessmentStageRepository: CsraAssessmentStageRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val prisonRegisterClient: PrisonRegisterClient,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonApiClient: PrisonApiClient,
  private val clock: Clock,
) {
  fun getCsraReviewById(id: UUID): CsraReview? = csraReviewRepository.findByIdOrNull(id)?.toDto()

  /**
   * The CSRA rating counts for a prison's current roll (the homepage "CSRA ratings at <prison>" tiles).
   *
   * The roll comes from prisoner-search; each roll member's current rating comes from our own data
   * (their latest review). "No rating" = roll − prisoners with a current high/standard rating, so it
   * counts both prisoners with no CSRA record and those whose latest review has no saved rating.
   */
  fun getPrisonRatingSummary(prisonId: String): CsraPrisonRatingSummary {
    val roll = prisonerSearchClient.getPrisonRoll(prisonId)
    val counts = roll.chunked(RATING_COUNT_BATCH_SIZE)
      .flatMap { csraReviewRepository.countCurrentRatingsByPrisonerNumberIn(it) }

    val highRisk = counts.filter { it.currentResult in HIGH_RESULT_NAMES }.sumOf { it.count }.toInt()
    val standardRisk = counts.filter { it.currentResult == CsraResult.STANDARD.name }.sumOf { it.count }.toInt()

    return CsraPrisonRatingSummary(
      prisonId = prisonId,
      total = roll.size,
      highRisk = highRisk,
      standardRisk = standardRisk,
      noRating = roll.size - highRisk - standardRisk,
    )
  }

  /**
   * A paged, filtered, sorted list of a prison's current prisoners with their current CSRA rating
   * (the "CSRA ratings for all prisoners" screen). The roll (and names) comes from prisoner-search;
   * each member's current rating is their latest review (same definition as [getPrisonRatingSummary]).
   * Prisoners with no review — or whose latest review has no saved rating — are shown as "No rating".
   *
   * The join, filter, sort and paging are done in memory: names live in prisoner-search and no-rating
   * prisoners have no `csra_review` row, so none of it can be pushed to the database or prisoner-search.
   */
  fun getPrisonPrisoners(
    prisonId: String,
    ratings: List<CsraRatingFilter>?,
    assessmentTypes: List<CsraAssessmentTypeBucket>?,
    fromDate: LocalDate?,
    toDate: LocalDate?,
    sort: CsraPrisonerSortField,
    direction: CsraSortDirection,
    page: Int,
    size: Int,
  ): CsraPrisonPrisonerList {
    val members = prisonerSearchClient.getPrisonRollMembers(prisonId)
    val currentByPrisoner = members.map { it.prisonerNumber }
      .chunked(RATING_COUNT_BATCH_SIZE)
      .flatMap { csraReviewRepository.findCurrentReviewsByPrisonerNumberIn(it) }
      .associateBy { it.prisonerNumber }

    val prisoners = members.map { member ->
      val row = currentByPrisoner[member.prisonerNumber]
      val rating = (row?.finalResult ?: row?.interimResult)?.let { CsraResult.valueOf(it) }
      CsraPrisonPrisoner(
        prisonerNumber = member.prisonerNumber,
        firstName = member.firstName,
        lastName = member.lastName,
        rating = rating,
        provisional = row != null && row.finalResult == null && row.interimResult != null,
        assessmentType = if (rating != null) CsraType.valueOf(row!!.type).toAssessmentBucket() else null,
        assessedOn = if (rating != null) (row!!.finalResultDate ?: row.assessmentDate) else null,
      )
    }

    val ratingValues = ratings?.map { it.toResult() }?.toSet()
    val filtered = prisoners.filter { p ->
      (ratingValues == null || p.rating in ratingValues) &&
        (assessmentTypes == null || p.assessmentType in assessmentTypes) &&
        (fromDate == null || (p.assessedOn != null && !p.assessedOn.isBefore(fromDate))) &&
        (toDate == null || (p.assessedOn != null && !p.assessedOn.isAfter(toDate)))
    }

    val sorted = filtered.sortedWith(comparatorFor(sort, direction).thenBy { it.prisonerNumber })

    val fromIndex = (page * size).coerceIn(0, sorted.size)
    val toIndex = (fromIndex + size).coerceIn(0, sorted.size)
    return CsraPrisonPrisonerList(
      content = sorted.subList(fromIndex, toIndex),
      page = page,
      size = size,
      totalElements = sorted.size.toLong(),
      totalPages = if (size <= 0) 0 else (sorted.size + size - 1) / size,
    )
  }

  private fun comparatorFor(sort: CsraPrisonerSortField, direction: CsraSortDirection): Comparator<CsraPrisonPrisoner> {
    val base: Comparator<CsraPrisonPrisoner> = when (sort) {
      CsraPrisonerSortField.NAME -> compareBy({ it.lastName?.lowercase() }, { it.firstName?.lowercase() })
      CsraPrisonerSortField.ASSESSMENT_TYPE -> compareBy { it.assessmentType }
      CsraPrisonerSortField.ASSESSED_ON -> compareBy { it.assessedOn }
      CsraPrisonerSortField.RATING -> compareBy { ratingSortOrder(it.rating) }
    }
    return if (direction == CsraSortDirection.DESC) base.reversed() else base
  }

  /**
   * The "high risk prisoners due for review" worklist for a prison: everyone currently in the prison
   * whose current rating is high-risk AND who has a scheduled next review date (csra_next_review).
   * Not paginated. Also returns the distinct rating types present (for the dynamic filter checkboxes),
   * computed over the whole establishment's due set (unaffected by the applied filters).
   */
  fun getHighRiskDueForReview(
    prisonId: String,
    ratingTypes: List<CsraHighRiskType>?,
    reviewDateFrom: LocalDate?,
    reviewDateTo: LocalDate?,
    sort: CsraHighRiskSortField,
    direction: CsraSortDirection,
  ): CsraHighRiskDueForReview {
    val numbers = prisonerSearchClient.getPrisonRollMembers(prisonId)
    val currentByPrisoner = numbers.map { it.prisonerNumber }.chunked(RATING_COUNT_BATCH_SIZE)
      .flatMap { csraReviewRepository.findCurrentReviewsByPrisonerNumberIn(it) }
      .associateBy { it.prisonerNumber }
    val nextReviewDateByPrisoner = numbers.map { it.prisonerNumber }.chunked(RATING_COUNT_BATCH_SIZE)
      .flatMap { csraNextReviewRepository.findAllByPrisonerNumberIn(it) }
      .mapNotNull { entity -> entity.nextReviewDate?.let { entity.prisonerNumber to it } }
      .toMap()

    val dueSet = numbers.mapNotNull { member ->
      val reviewDueBy = nextReviewDateByPrisoner[member.prisonerNumber] ?: return@mapNotNull null
      val row = currentByPrisoner[member.prisonerNumber] ?: return@mapNotNull null
      val rating = (row.finalResult ?: row.interimResult)?.let { CsraResult.valueOf(it) } ?: return@mapNotNull null
      if (!rating.isHigh()) return@mapNotNull null
      val provisional = row.finalResult == null && row.interimResult != null
      CsraHighRiskReviewRow(
        prisonerNumber = member.prisonerNumber,
        firstName = member.firstName,
        lastName = member.lastName,
        reviewDueBy = reviewDueBy,
        ratingType = CsraHighRiskType.from(rating, provisional)!!,
        rating = rating,
        provisional = provisional,
        lastRatingSource = CsraType.valueOf(row.type).toAssessmentBucket(),
        lastRatingDate = row.finalResultDate ?: row.assessmentDate,
      )
    }

    val availableRatingTypes = dueSet.map { it.ratingType }.distinct().sorted()

    val filtered = dueSet.filter { r ->
      (ratingTypes == null || r.ratingType in ratingTypes) &&
        (reviewDateFrom == null || !r.reviewDueBy.isBefore(reviewDateFrom)) &&
        (reviewDateTo == null || !r.reviewDueBy.isAfter(reviewDateTo))
    }

    val sorted = filtered.sortedWith(highRiskComparatorFor(sort, direction).thenBy { it.prisonerNumber })

    return CsraHighRiskDueForReview(
      content = sorted,
      totalResults = sorted.size,
      availableRatingTypes = availableRatingTypes,
    )
  }

  private fun highRiskComparatorFor(sort: CsraHighRiskSortField, direction: CsraSortDirection): Comparator<CsraHighRiskReviewRow> {
    val base: Comparator<CsraHighRiskReviewRow> = when (sort) {
      CsraHighRiskSortField.REVIEW_DUE_BY -> compareBy({ it.reviewDueBy }, { it.lastName?.lowercase() }, { it.firstName?.lowercase() })
      CsraHighRiskSortField.NAME -> compareBy({ it.lastName?.lowercase() }, { it.firstName?.lowercase() })
    }
    return if (direction == CsraSortDirection.DESC) base.reversed() else base
  }

  /**
   * A prison's in-progress CSRA initial assessments (the "assessments in progress" worklist), split into
   * those started with no rating yet and those with a provisional rating awaiting a final. Driven solely
   * by our data (new-model assessments at this prison with no final result); names are resolved from
   * prisoner-search for the small in-progress set.
   */
  fun getAssessmentsInProgress(prisonId: String): CsraAssessmentsInProgress {
    val reviews = csraReviewRepository
      .findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus(prisonId, CsraType.CSRA_INITIAL_REVIEW, CsraReviewStatus.IN_PROGRESS)
    val names = prisonerSearchClient.getPrisonerNames(reviews.map { it.prisonerNumber })
    val provisionalStageByReviewId = csraAssessmentStageRepository.findAllByCsraReviewIdIn(reviews.mapNotNull { it.id })
      .filter { it.stage == CsraAssessmentStage.PROVISIONAL }
      .associateBy { it.csraReview.id }

    val started = reviews.filter { it.interimResult == null }.map { r ->
      val name = names[r.prisonerNumber]
      CsraAssessmentStartedRow(
        reviewId = r.id!!,
        prisonerNumber = r.prisonerNumber,
        firstName = name?.firstName,
        lastName = name?.lastName,
        startedOn = r.assessmentDate,
        startedBy = r.createdBy,
      )
    }.sortedWith(compareBy({ it.startedOn }, { it.lastName?.lowercase() }, { it.firstName?.lowercase() }))

    val provisional = reviews.filter { it.interimResult != null }.map { r ->
      val name = names[r.prisonerNumber]
      val stage = provisionalStageByReviewId[r.id]
      CsraProvisionalRatingRow(
        reviewId = r.id!!,
        prisonerNumber = r.prisonerNumber,
        firstName = name?.firstName,
        lastName = name?.lastName,
        assessedOn = stage?.completedAt?.toLocalDate() ?: r.interimResultDate ?: r.assessmentDate,
        assessedBy = stage?.completedBy ?: r.createdBy,
        rating = r.interimResult!!,
      )
    }.sortedWith(compareBy({ it.assessedOn }, { it.lastName?.lowercase() }, { it.firstName?.lowercase() }))

    return CsraAssessmentsInProgress(assessmentStarted = started, provisionalRatingEntered = provisional)
  }

  /**
   * A prison's in-progress CSRA reviews (the "reviews in progress" worklist): new-model reviews at this
   * prison with no final result. Names resolved from prisoner-search.
   */
  fun getReviewsInProgress(prisonId: String): CsraReviewsInProgress {
    val reviews = csraReviewRepository
      .findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus(prisonId, CsraType.CSRA_REVIEW, CsraReviewStatus.IN_PROGRESS)
    val names = prisonerSearchClient.getPrisonerNames(reviews.map { it.prisonerNumber })
    val content = reviews.map { r ->
      val name = names[r.prisonerNumber]
      CsraReviewInProgressRow(
        reviewId = r.id!!,
        prisonerNumber = r.prisonerNumber,
        firstName = name?.firstName,
        lastName = name?.lastName,
        startedOn = r.assessmentDate,
        startedBy = r.createdBy,
      )
    }.sortedWith(compareBy({ it.startedOn }, { it.lastName?.lowercase() }, { it.firstName?.lowercase() }))
    return CsraReviewsInProgress(content = content, totalResults = content.size)
  }

  /**
   * Prisoners who arrived at [prisonId] in the last [days] days and are still in the establishment (the
   * "recent arrivals" worklist). Arrivals come from prison-api movements (the source of truth); anyone no
   * longer in the establishment (released or moved on) is excluded via the prisoner-search roll. One row
   * per prisoner — their most recent arrival in the window.
   */
  fun getRecentArrivals(prisonId: String, days: Int, arrivalTypes: List<CsraArrivalType>?): CsraRecentArrivals {
    val today = LocalDate.now(clock)
    val fromDate = today.minusDays((days - 1).toLong())
    val roll = prisonerSearchClient.getPrisonRoll(prisonId).toSet()

    val arrivals = prisonApiClient.getArrivals(prisonId, fromDate.atStartOfDay())
      .mapNotNull { movement ->
        val type = CsraArrivalType.fromMovementType(movement.movementType) ?: return@mapNotNull null
        val arrivedAt = movement.movementDateTime ?: return@mapNotNull null
        if (movement.offenderNo !in roll) return@mapNotNull null
        CsraArrivalRow(
          prisonerNumber = movement.offenderNo,
          firstName = movement.firstName,
          lastName = movement.lastName,
          dateOfBirth = movement.dateOfBirth,
          arrivalType = type,
          arrivedAt = arrivedAt,
          location = movement.location,
        )
      }
      // One row per prisoner: their most recent arrival in the window.
      .groupBy { it.prisonerNumber }
      .map { (_, rows) -> rows.maxBy { it.arrivedAt } }

    val arrivalTypeCounts = CsraArrivalType.entries.associateWith { type -> arrivals.count { it.arrivalType == type } }

    val filtered = arrivals
      .filter { arrivalTypes == null || it.arrivalType in arrivalTypes }
      .sortedByDescending { it.arrivedAt }

    return CsraRecentArrivals(
      arrivals = filtered,
      totalResults = filtered.size,
      arrivalTypeCounts = arrivalTypeCounts,
      fromDate = fromDate,
      toDate = today,
    )
  }

  fun getCurrentRating(prisonerNumber: String): CsraCurrentRating {
    val review = csraReviewRepository
      .findFirstByPrisonerNumberAndStatusNotOrderByAssessmentDateDescIdDesc(prisonerNumber, CsraReviewStatus.ARCHIVED)
      ?: return CsraCurrentRating(
        prisonerNumber = prisonerNumber,
        status = CsraRatingStatus.NO_RATING,
        rating = null,
        provisional = false,
        reviewId = null,
        prisonId = null,
        assessmentComment = null,
        provisionalAssessmentComment = null,
        riskTo = emptyList(),
        vulnerabilities = emptyList(),
        provisionalDate = null,
        finalDate = null,
        nextReviewDate = null,
        startedBy = null,
        startedAt = null,
      )

    val status = when {
      review.finalResult != null -> CsraRatingStatus.COMPLETE
      review.interimResult != null -> CsraRatingStatus.PROVISIONAL
      else -> CsraRatingStatus.IN_PROGRESS
    }

    val stages = csraAssessmentStageRepository.findAllByCsraReviewId(review.id!!)
    val finalStage = stages.firstOrNull { it.stage == CsraAssessmentStage.FINAL }
    val provisionalStage = stages.firstOrNull { it.stage == CsraAssessmentStage.PROVISIONAL }
    // The stage that produced the current rating: the final stage once complete, otherwise the provisional.
    val ratingStage = finalStage ?: provisionalStage
    // Migrated legacy reviews have no stages; their comment lives on the adjacent NOMIS record.
    val nomis = if (stages.isEmpty()) csraReviewNomisRepository.findByCsraReviewId(review.id!!) else null

    return CsraCurrentRating(
      prisonerNumber = prisonerNumber,
      status = status,
      rating = review.finalResult ?: review.interimResult,
      provisional = status == CsraRatingStatus.PROVISIONAL,
      reviewId = review.id,
      prisonId = ratingStage?.prisonId ?: review.prisonId,
      assessmentComment = finalStage?.assessmentComment ?: nomis?.reviewComment ?: nomis?.comment,
      provisionalAssessmentComment = provisionalStage?.assessmentComment,
      riskTo = ratingStage?.riskTo?.map { CsraRiskToDetail(it.category, it.details) }.orEmpty(),
      vulnerabilities = ratingStage?.vulnerabilities?.map { CsraVulnerabilityDetail(it.category, it.details) }.orEmpty(),
      provisionalDate = provisionalStage?.completedAt?.toLocalDate() ?: review.interimResultDate,
      finalDate = finalStage?.completedAt?.toLocalDate() ?: review.finalResultDate,
      nextReviewDate = csraNextReviewRepository.findByPrisonerNumber(prisonerNumber)?.nextReviewDate,
      startedBy = review.createdBy,
      startedAt = review.createdAt,
    )
  }

  fun getCsraHistory(
    prisonerNumber: String,
    page: Int,
    size: Int,
    fromDate: LocalDate?,
    toDate: LocalDate?,
    establishments: List<String>?,
    ratings: List<CsraRatingBucket>?,
  ): CsraReviewHistory {
    val results = ratings?.flatMap { it.toResults() }?.distinct()?.takeIf { it.isNotEmpty() }
    val spec = CsraReviewSpecifications.history(prisonerNumber, fromDate, toDate, establishments, results)
    val pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("assessmentDate"), Sort.Order.desc("id")))
    val reviews = csraReviewRepository.findAll(spec, pageable)

    val reviewIds = reviews.content.mapNotNull { it.id }
    val commentByReviewId = resolveComments(reviewIds)
    val content = reviews.content.map { it.toSummary(commentByReviewId[it.id]) }

    return CsraReviewHistory(
      summary = buildSummary(prisonerNumber),
      content = content,
      page = reviews.number,
      size = reviews.size,
      totalElements = reviews.totalElements,
      totalPages = reviews.totalPages,
    )
  }

  private fun buildSummary(prisonerNumber: String): CsraReviewHistorySummary {
    val rows = csraReviewRepository.findSummaryRows(prisonerNumber)
    val highDates = rows.filter { it.result.isHigh() }.map { it.assessmentDate }
    val dates = rows.map { it.assessmentDate }
    return CsraReviewHistorySummary(
      totalCsras = rows.size,
      highCount = highDates.size,
      standardCount = rows.size - highDates.size,
      firstAssessmentDate = dates.minOrNull(),
      lastAssessmentDate = dates.maxOrNull(),
      lastHighDate = highDates.maxOrNull(),
      establishments = buildEstablishments(rows),
    )
  }

  /** The distinct establishments across a prisoner's whole history, name-resolved and name-sorted. */
  private fun buildEstablishments(rows: List<CsraSummaryRow>): List<CsraEstablishment> {
    val prisonIds = rows.mapNotNull { it.prisonId }.distinct()
    if (prisonIds.isEmpty()) return emptyList()
    val names = prisonRegisterClient.getPrisonNames()
    return prisonIds
      .map { CsraEstablishment(prisonId = it, prisonName = names[it] ?: it) }
      .sortedBy { it.prisonName }
  }

  /**
   * Resolves the display comment for each review. The comment lives in a different place per row type:
   * new-model reviews carry it on the FINAL (or, failing that, PROVISIONAL) assessment stage; migrated
   * legacy reviews carry it on the adjacent NOMIS record. Loaded in batch to avoid N+1 queries.
   */
  private fun resolveComments(reviewIds: List<UUID>): Map<UUID, String?> {
    if (reviewIds.isEmpty()) return emptyMap()
    val stagesByReviewId = csraAssessmentStageRepository.findAllByCsraReviewIdIn(reviewIds)
      .groupBy { it.csraReview.id }
    val nomisByReviewId = csraReviewNomisRepository.findAllByCsraReviewIdIn(reviewIds)
      .associateBy { it.csraReview.id }
    return reviewIds.associateWith { id ->
      stageComment(stagesByReviewId[id]) ?: nomisComment(nomisByReviewId[id])
    }
  }

  private fun stageComment(stages: List<CsraAssessmentStageEntity>?): String? {
    if (stages.isNullOrEmpty()) return null
    return stages.firstOrNull { it.stage == CsraAssessmentStage.FINAL }?.assessmentComment
      ?: stages.firstOrNull { it.stage == CsraAssessmentStage.PROVISIONAL }?.assessmentComment
  }

  private fun nomisComment(nomis: CsraReviewNomisEntity?): String? = nomis?.reviewComment ?: nomis?.comment

  private fun CsraReviewEntity.toSummary(reviewComment: String?) = CsraReviewSummary(
    id = id!!,
    type = type,
    rating = finalResult ?: interimResult!!,
    reviewComment = reviewComment,
    prisonId = prisonId,
    recordedDate = finalResultDate ?: assessmentDate,
  )

  private companion object {
    /** The stored [CsraResult] names that count as high risk, for matching the native count projection. */
    private val HIGH_RESULT_NAMES: Set<String> = HIGH_RESULTS.map { it.name }.toSet()

    /** Chunk the roll when querying counts so the `IN (...)` list stays a sane size for large prisons. */
    private const val RATING_COUNT_BATCH_SIZE = 1000

    /** Severity ordering for the RATING sort: No rating < Standard < High < High-general < High-specific. */
    private fun ratingSortOrder(rating: CsraResult?): Int = when (rating) {
      null -> 0
      CsraResult.STANDARD -> 1
      CsraResult.HIGH -> 2
      CsraResult.HIGH_GENERAL -> 3
      CsraResult.HIGH_SPECIFIC -> 4
    }
  }
}
