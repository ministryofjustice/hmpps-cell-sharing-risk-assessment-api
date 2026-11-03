package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRatingStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional
import java.util.UUID

class CsraReviewServiceTest {
  private val reviews: CsraReviewRepository = mock()
  private val nomis: CsraReviewNomisRepository = mock()
  private val stages: CsraAssessmentStageRepository = mock()
  private val currentRatings: CsraCurrentRatingRepository = mock()
  private val nextReviews: CsraNextReviewRepository = mock()
  private val prisons: PrisonRegisterClient = mock()
  private val service = CsraReviewService(reviews, nomis, stages, nextReviews, currentRatings, prisons, mock(), mock(), Clock.systemUTC())

  private fun review(
    finalResult: CsraResult? = null,
    interimResult: CsraResult? = null,
    status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,
    nextReviewDate: LocalDate? = null,
  ): CsraReviewEntity {
    val review = CsraReviewEntity(
      id = UUID.randomUUID(),
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      assessmentDate = LocalDate.parse("2025-01-01"),
      type = CsraType.CSRA_REVIEW,
      status = status,
      finalResult = finalResult,
      interimResult = interimResult,
      nextReviewDate = nextReviewDate,
      createdAt = LocalDateTime.parse("2025-01-01T10:00:00"),
      createdBy = "USER",
    )
    whenever(reviews.findById(review.id!!)).thenReturn(Optional.of(review))
    whenever(stages.findAllByCsraReviewId(review.id!!)).thenReturn(emptyList())
    whenever(prisons.getPrisonNames()).thenReturn(mapOf("LEI" to "Leeds (HMP)"))
    return review
  }

  @Test
  fun `both rating reads include separate provisional and final completion details`() {
    val review = review(finalResult = CsraResult.STANDARD, interimResult = CsraResult.HIGH_GENERAL, status = CsraReviewStatus.COMPLETE)
    val provisionalAt = LocalDateTime.parse("2025-01-01T10:30:15")
    val finalAt = LocalDateTime.parse("2025-01-02T11:20:45")
    whenever(stages.findAllByCsraReviewId(review.id!!)).thenReturn(
      listOf(
        CsraAssessmentStageEntity(csraReview = review, stage = CsraAssessmentStage.PROVISIONAL, completedBy = "PROVISIONAL_USER", completedAt = provisionalAt),
        CsraAssessmentStageEntity(csraReview = review, stage = CsraAssessmentStage.FINAL, completedBy = "FINAL_USER", completedAt = finalAt),
      ),
    )
    whenever(currentRatings.findByPrisonerNumber(review.prisonerNumber)).thenReturn(
      CsraCurrentRatingEntity(
        prisonerNumber = review.prisonerNumber,
        rating = review.finalResult,
        setByReviewId = review.id,
        setReason = CsraRatingSetReason.RATING_SAVED,
        setAt = finalAt,
      ),
    )

    listOf(service.getDetailedRatingById(review.id!!)!!, service.getCurrentRating(review.prisonerNumber)).forEach { result ->
      assertThat(result.provisionalCompletedBy).isEqualTo("PROVISIONAL_USER")
      assertThat(result.finalCompletedBy).isEqualTo("FINAL_USER")
      assertThat(result.provisionalTime).isEqualTo(provisionalAt.toLocalTime())
      assertThat(result.finalTime).isEqualTo(finalAt.toLocalTime())
      assertThat(result.provisionalDate).isEqualTo(provisionalAt.toLocalDate())
      assertThat(result.finalDate).isEqualTo(finalAt.toLocalDate())
    }
  }

  @Test
  fun `detailed rating includes superseded records without consulting current projections`() {
    val date = LocalDate.parse("2026-01-01")
    val review = review(finalResult = CsraResult.HIGH_SPECIFIC, status = CsraReviewStatus.COMPLETE, nextReviewDate = date)
    review.supersededAt = LocalDateTime.parse("2026-02-01T10:00:00")

    val result = service.getDetailedRatingById(review.id!!)!!

    assertThat(result.reviewId).isEqualTo(review.id)
    assertThat(result.rating).isEqualTo(CsraResult.HIGH_SPECIFIC)
    assertThat(result.status).isEqualTo(CsraRatingStatus.COMPLETE)
    assertThat(result.ratingStage).isEqualTo(CsraRatingStage.FINAL)
    assertThat(result.nextReviewDate).isEqualTo(date)
    assertThat(result.provisionalCompletedBy).isNull()
    assertThat(result.finalCompletedBy).isNull()
    assertThat(result.provisionalTime).isNull()
    assertThat(result.finalTime).isNull()
    assertThat(result.prisonName).isEqualTo("Leeds (HMP)")
    assertThat(result.inProgress).isNull()
    verifyNoInteractions(currentRatings, nextReviews)
  }

  @Test
  fun `detailed rating falls back to the selected NOMIS record for comments and next review date`() {
    val review = review(finalResult = CsraResult.HIGH, status = CsraReviewStatus.COMPLETE)
    val date = LocalDate.parse("2026-01-01")
    whenever(nomis.findByCsraReviewId(review.id!!))
      .thenReturn(CsraReviewNomisEntity(csraReview = review, reviewComment = "Legacy comment", nextReviewDate = date))

    val result = service.getDetailedRatingById(review.id!!)!!

    assertThat(result.assessmentComment).isEqualTo("Legacy comment")
    assertThat(result.nextReviewDate).isEqualTo(date)
  }

  @Test
  fun `detailed rating identifies interim work on the selected record`() {
    val review = review(interimResult = CsraResult.HIGH_GENERAL)
    whenever(stages.findAllByCsraReviewId(review.id!!)).thenReturn(
      listOf(
        CsraAssessmentStageEntity(
          csraReview = review,
          stage = CsraAssessmentStage.INTERIM,
          completedBy = "INTERIM_USER",
          completedAt = LocalDateTime.parse("2025-01-01T09:45:30"),
        ),
      ),
    )

    val result = service.getDetailedRatingById(review.id!!)!!

    assertThat(result.status).isEqualTo(CsraRatingStatus.PROVISIONAL)
    assertThat(result.provisional).isTrue()
    assertThat(result.ratingStage).isEqualTo(CsraRatingStage.INTERIM)
    assertThat(result.inProgress?.reviewId).isEqualTo(review.id)
    assertThat(result.provisionalCompletedBy).isEqualTo("INTERIM_USER")
    assertThat(result.provisionalTime).isEqualTo(LocalTime.parse("09:45:30"))
    assertThat(result.finalCompletedBy).isNull()
    assertThat(result.finalTime).isNull()
  }

  @Test
  fun `unrated draft detailed rating is in progress`() {
    val review = review()

    val result = service.getDetailedRatingById(review.id!!)!!

    assertThat(result.status).isEqualTo(CsraRatingStatus.IN_PROGRESS)
    assertThat(result.rating).isNull()
    assertThat(result.provisionalCompletedBy).isNull()
    assertThat(result.finalCompletedBy).isNull()
    assertThat(result.provisionalTime).isNull()
    assertThat(result.finalTime).isNull()
    assertThat(result.inProgress?.reviewId).isEqualTo(review.id)
  }

  @Test
  fun `closed unrated record is not reported as work in progress`() {
    val review = review(status = CsraReviewStatus.CLOSED)

    val result = service.getDetailedRatingById(review.id!!)!!

    assertThat(result.status).isEqualTo(CsraRatingStatus.NO_RATING)
    assertThat(result.inProgress).isNull()
  }

  @Test
  fun `current rating without a record has no completion details`() {
    val result = service.getCurrentRating("A1234BC")

    assertThat(result.provisionalCompletedBy).isNull()
    assertThat(result.finalCompletedBy).isNull()
    assertThat(result.provisionalTime).isNull()
    assertThat(result.finalTime).isNull()
  }

  @Test
  fun `missing and archived detailed ratings are not returned`() {
    val missingId = UUID.randomUUID()
    whenever(reviews.findById(missingId)).thenReturn(Optional.empty())
    val archived = review(status = CsraReviewStatus.ARCHIVED)

    assertThat(service.getDetailedRatingById(missingId)).isNull()
    assertThat(service.getDetailedRatingById(archived.id!!)).isNull()
    verifyNoInteractions(nomis, currentRatings, nextReviews)
  }
}
