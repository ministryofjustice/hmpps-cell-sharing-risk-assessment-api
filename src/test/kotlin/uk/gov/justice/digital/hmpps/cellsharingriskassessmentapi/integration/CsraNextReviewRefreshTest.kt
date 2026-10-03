package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How [uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraCurrentRatingService.refreshFromReviews]
 * derives the `csra_next_review` projection from the date each review set (SDIT-4297).
 */
class CsraNextReviewRefreshTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @BeforeEach
  fun clean() {
    csraNextReviewRepository.deleteAll()
    csraCurrentRatingRepository.deleteAll()
    csraReviewRepository.deleteAll()
  }

  private fun review(
    prisonerNumber: String = PRISONER_A,
    assessmentDate: String,
    finalResult: CsraResult? = null,
    interimResult: CsraResult? = null,
    nextReviewDate: String? = null,
    status: CsraReviewStatus = if (finalResult != null) CsraReviewStatus.COMPLETE else CsraReviewStatus.IN_PROGRESS,
    supersededAt: LocalDateTime? = null,
  ): CsraReviewEntity = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      assessmentDate = LocalDate.parse(assessmentDate),
      type = CsraType.CSRA_REVIEW,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { LocalDate.parse(assessmentDate) },
      finalResult = finalResult,
      finalResultDate = finalResult?.let { LocalDate.parse(assessmentDate) },
      nextReviewDate = nextReviewDate?.let(LocalDate::parse),
      status = status,
      supersededAt = supersededAt,
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  )

  private fun nextReview(prisonerNumber: String = PRISONER_A): CsraNextReviewEntity? = csraNextReviewRepository.findByPrisonerNumber(prisonerNumber)

  @Nested
  inner class Derivation {
    @Test
    fun `the date comes from the latest final-rated review`() {
      review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      val latest = review(assessmentDate = "2025-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-07-01")

      refreshCurrentRating(PRISONER_A)

      assertThat(nextReview()!!.setByReviewId).isEqualTo(latest.id)
      assertThat(nextReview()!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-07-01"))
    }

    @Test
    fun `an interim rating leaves the previous completed review's date standing`() {
      val completed = review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      review(assessmentDate = "2024-12-20", interimResult = CsraResult.STANDARD)

      refreshCurrentRating(PRISONER_A)

      assertThat(csraCurrentRatingRepository.findByPrisonerNumber(PRISONER_A)!!.rating).isEqualTo(CsraResult.STANDARD)
      assertThat(nextReview()!!.setByReviewId).isEqualTo(completed.id)
      assertThat(nextReview()!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-01-01"))
    }

    @Test
    fun `a latest review that set no date leaves the prisoner with none, rather than an older review's`() {
      review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      val standard = review(assessmentDate = "2025-01-01", finalResult = CsraResult.STANDARD)

      refreshCurrentRating(PRISONER_A)

      assertThat(nextReview()!!.setByReviewId).isEqualTo(standard.id)
      assertThat(nextReview()!!.nextReviewDate).isNull()
    }

    @Test
    fun `archived reviews are ignored`() {
      val completed = review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      review(assessmentDate = "2025-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2026-01-01", status = CsraReviewStatus.ARCHIVED)

      refreshCurrentRating(PRISONER_A)

      assertThat(nextReview()!!.setByReviewId).isEqualTo(completed.id)
    }

    @Test
    fun `a readmission's superseded reviews no longer set a date, so the row goes`() {
      review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      refreshCurrentRating(PRISONER_A)
      assertThat(nextReview()).isNotNull()

      // Released and readmitted: the old review is superseded and a new assessment reaches a provisional rating.
      csraReviewRepository.saveAllAndFlush(
        csraReviewRepository.findAllByPrisonerNumber(PRISONER_A).onEach { it.supersededAt = LocalDateTime.parse("2025-03-01T09:00:00") },
      )
      review(assessmentDate = "2025-03-02", interimResult = CsraResult.HIGH_GENERAL)
      refreshCurrentRating(PRISONER_A)

      assertThat(nextReview()).isNull()
    }

    @Test
    fun `a NOMIS review loaded before the date was stored on the review falls back to the NOMIS copy`() {
      val legacy = review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH)
      csraReviewNomisRepository.saveAndFlush(CsraReviewNomisEntity(csraReview = legacy, nextReviewDate = LocalDate.parse("2024-07-01")))

      refreshCurrentRating(PRISONER_A)

      assertThat(nextReview()!!.nextReviewDate).isEqualTo(LocalDate.parse("2024-07-01"))
    }

    @Test
    fun `an unchanged date is left alone`() {
      review(assessmentDate = "2024-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-01-01")
      csraCurrentRatingService.refreshFromReviews(PRISONER_A, "FIRST_USER")

      csraCurrentRatingService.refreshFromReviews(PRISONER_A, "SECOND_USER")

      assertThat(nextReview()!!.updatedBy).isEqualTo("FIRST_USER")
    }
  }

  /**
   * The ticket's scenarios. A booking move repoints the named reviews and refreshes both prisoner numbers,
   * which is all this does.
   */
  @Nested
  inner class BookingMove {
    private fun move(vararg reviews: CsraReviewEntity, to: String) {
      val from = reviews.first().prisonerNumber
      reviews.forEach { it.prisonerNumber = to }
      csraReviewRepository.saveAllAndFlush(reviews.toList())
      refreshCurrentRating(from)
      refreshCurrentRating(to)
    }

    @Test
    fun `when A's latest reviews move to B, A falls back to the date its latest remaining review set`() {
      val remaining = review(assessmentDate = "2022-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2022-07-01")
      val moved1 = review(assessmentDate = "2025-01-01", finalResult = CsraResult.STANDARD)
      val moved2 = review(assessmentDate = "2025-02-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-08-01")
      refreshCurrentRating(PRISONER_A)

      move(moved1, moved2, to = PRISONER_B)

      assertThat(nextReview(PRISONER_A)!!.setByReviewId).isEqualTo(remaining.id)
      assertThat(nextReview(PRISONER_A)!!.nextReviewDate).isEqualTo(LocalDate.parse("2022-07-01"))
      assertThat(nextReview(PRISONER_B)!!.setByReviewId).isEqualTo(moved2.id)
      assertThat(nextReview(PRISONER_B)!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-08-01"))
    }

    @Test
    fun `when the moved reviews were not A's latest but are B's, A keeps its date and B takes the moved one`() {
      val moved = review(assessmentDate = "2023-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2023-07-01")
      val aLatest = review(assessmentDate = "2025-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-07-01")
      review(prisonerNumber = PRISONER_B, assessmentDate = "2020-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2020-07-01")
      refreshCurrentRating(PRISONER_A)
      refreshCurrentRating(PRISONER_B)

      move(moved, to = PRISONER_B)

      assertThat(nextReview(PRISONER_A)!!.setByReviewId).isEqualTo(aLatest.id)
      assertThat(nextReview(PRISONER_A)!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-07-01"))
      assertThat(nextReview(PRISONER_B)!!.setByReviewId).isEqualTo(moved.id)
      assertThat(nextReview(PRISONER_B)!!.nextReviewDate).isEqualTo(LocalDate.parse("2023-07-01"))
    }

    @Test
    fun `when the moved reviews are older than B's own, B keeps its date`() {
      val moved = review(assessmentDate = "2021-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2021-07-01")
      val bLatest = review(prisonerNumber = PRISONER_B, assessmentDate = "2025-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-07-01")
      refreshCurrentRating(PRISONER_A)
      refreshCurrentRating(PRISONER_B)

      move(moved, to = PRISONER_B)

      assertThat(nextReview(PRISONER_B)!!.setByReviewId).isEqualTo(bLatest.id)
      assertThat(nextReview(PRISONER_B)!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-07-01"))
    }

    @Test
    fun `when A is left with no completed review, A's date goes`() {
      val moved = review(assessmentDate = "2025-01-01", finalResult = CsraResult.HIGH_GENERAL, nextReviewDate = "2025-07-01")
      refreshCurrentRating(PRISONER_A)

      move(moved, to = PRISONER_B)

      assertThat(nextReview(PRISONER_A)).isNull()
      assertThat(nextReview(PRISONER_B)!!.nextReviewDate).isEqualTo(LocalDate.parse("2025-07-01"))
    }
  }

  private companion object {
    const val PRISONER_A = "A1111AA"
    const val PRISONER_B = "B2222BB"
  }
}
