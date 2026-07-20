package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraAssessmentTypeBucket
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraCurrentRatingService
import java.time.LocalDate
import java.time.LocalDateTime

class CsraCurrentRatingServiceTest : SqsIntegrationTestBase() {

  private val service: CsraCurrentRatingService get() = csraCurrentRatingService

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @BeforeEach
  fun clean() {
    csraCurrentRatingRepository.deleteAll()
    csraReviewRepository.deleteAll()
  }

  private fun review(
    assessmentDate: LocalDate,
    finalResult: CsraResult? = null,
    interimResult: CsraResult? = null,
    type: CsraType = CsraType.CSRA_INITIAL_REVIEW,
    status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      assessmentDate = assessmentDate,
      type = type,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { assessmentDate },
      finalResult = finalResult,
      finalResultDate = finalResult?.let { assessmentDate },
      status = status,
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  )

  @Test
  fun `refresh sets the projection from the latest rated non-archived review`() {
    review(LocalDate.parse("2024-01-01"), finalResult = CsraResult.HIGH) // older
    val latest = review(LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD, type = CsraType.CSRA_REVIEW)
    review(LocalDate.parse("2027-01-01"), finalResult = CsraResult.HIGH, status = CsraReviewStatus.ARCHIVED) // archived, ignored

    service.refreshFromReviews("A1234BC")

    val current = csraCurrentRatingRepository.findByPrisonerNumber("A1234BC")!!
    assertThat(current.rating).isEqualTo(CsraResult.STANDARD)
    assertThat(current.provisional).isFalse()
    assertThat(current.assessmentType).isEqualTo(CsraAssessmentTypeBucket.REVIEW)
    assertThat(current.ratingDate).isEqualTo(LocalDate.parse("2026-02-01"))
    assertThat(current.setByReviewId).isEqualTo(latest.id)
  }

  @Test
  fun `a newly started unrated assessment does not clear the current rating (strict R-06)`() {
    review(LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD)
    service.refreshFromReviews("A1234BC")
    // A fresh assessment is started (no rating yet) and the projection is refreshed again.
    review(LocalDate.parse("2026-03-01"))
    service.refreshFromReviews("A1234BC")

    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A1234BC")!!.rating).isEqualTo(CsraResult.STANDARD)
  }

  @Test
  fun `refresh with only unrated reviews leaves No rating`() {
    review(LocalDate.parse("2026-03-01"))

    service.refreshFromReviews("A1234BC")

    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A1234BC")!!.rating).isNull()
  }

  @Test
  fun `reset writes No rating`() {
    review(LocalDate.parse("2026-02-01"), interimResult = CsraResult.HIGH_GENERAL)
    service.refreshFromReviews("A1234BC")
    assertThat(csraCurrentRatingRepository.findByPrisonerNumber("A1234BC")!!.rating).isEqualTo(CsraResult.HIGH_GENERAL)

    service.resetToNoRating("A1234BC", "SYSTEM")

    val current = csraCurrentRatingRepository.findByPrisonerNumber("A1234BC")!!
    assertThat(current.rating).isNull()
    assertThat(current.setByReviewId).isNull()
    assertThat(current.setReason.name).isEqualTo("NO_RATING_ON_READMISSION")
  }
}
