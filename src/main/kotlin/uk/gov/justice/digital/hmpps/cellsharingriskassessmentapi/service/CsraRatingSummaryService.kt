package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import jakarta.validation.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingSummary
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.describeRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.describeRatingShort
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.reviewDueDateToShow
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate

/**
 * The current CSRA rating in brief for other services (MAPA-516). Gives the same rating, status and wording
 * as [CsraReviewService.getCurrentRating] but reads only the current-rating and next-review projections (and,
 * for prisoners with no rating, whether anything is in progress), never the review detail or the prison
 * register, and does so in a fixed number of queries however many prisoners are asked for.
 */
@Service
@Transactional(readOnly = true)
class CsraRatingSummaryService(
  private val csraCurrentRatingRepository: CsraCurrentRatingRepository,
  private val csraNextReviewRepository: CsraNextReviewRepository,
  private val csraReviewRepository: CsraReviewRepository,
) {
  companion object {
    const val MAX_PRISONERS = 1000
  }

  fun getRatingSummary(prisonerNumber: String): CsraRatingSummary = getRatingSummaries(listOf(prisonerNumber)).single()

  fun getRatingSummaries(prisonerNumbers: List<String>): List<CsraRatingSummary> {
    val requested = prisonerNumbers.distinct()
    if (requested.size > MAX_PRISONERS) {
      throw ValidationException("A maximum of $MAX_PRISONERS prisoner numbers can be requested at once, but ${requested.size} were given")
    }
    if (requested.isEmpty()) return emptyList()

    val current = csraCurrentRatingRepository.findAllByPrisonerNumberIn(requested).associateBy { it.prisonerNumber }
    val nextReview = csraNextReviewRepository.findAllByPrisonerNumberIn(requested).associate { it.prisonerNumber to it.nextReviewDate }
    // Only prisoners without a rating can read as "in progress", so only they need the extra lookup.
    val unrated = requested.filter { current[it]?.hasRating() != true }
    val inProgress = if (unrated.isEmpty()) {
      emptySet()
    } else {
      csraReviewRepository.findPrisonerNumbersWithStatus(unrated, CsraReviewStatus.IN_PROGRESS).toSet()
    }

    return requested.map { summarise(it, current[it], nextReview[it], it in inProgress) }
  }

  private fun summarise(
    prisonerNumber: String,
    current: CsraCurrentRatingEntity?,
    nextReviewDate: LocalDate?,
    inProgress: Boolean,
  ): CsraRatingSummary {
    val rated = current?.takeIf { it.hasRating() }
    val rating = rated?.rating
    val ratingStage = rated?.ratingStage
    val status = when {
      rated != null -> if (rated.provisional) CsraRatingStatus.PROVISIONAL else CsraRatingStatus.COMPLETE
      inProgress -> CsraRatingStatus.IN_PROGRESS
      else -> CsraRatingStatus.NO_RATING
    }
    return CsraRatingSummary(
      prisonerNumber = prisonerNumber,
      status = status,
      rating = rating,
      ratingStage = ratingStage,
      ratingDate = rated?.ratingDate,
      reviewId = rated?.setByReviewId,
      ratingDescription = describeRating(rating, ratingStage),
      ratingShortDescription = describeRatingShort(rating),
      reviewDueDate = reviewDueDateToShow(rating, ratingStage, nextReviewDate),
    )
  }

  // Matches getCurrentRating, which only treats a rating as current when it points at the review that set it.
  private fun CsraCurrentRatingEntity.hasRating() = rating != null && setByReviewId != null
}
