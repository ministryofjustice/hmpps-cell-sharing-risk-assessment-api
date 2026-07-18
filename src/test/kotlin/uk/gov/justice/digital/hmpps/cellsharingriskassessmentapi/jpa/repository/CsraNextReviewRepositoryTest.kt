package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.TestBase
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CsraNextReviewRepositoryTest : TestBase() {

  @Autowired
  lateinit var repository: CsraNextReviewRepository

  @Autowired
  lateinit var reviewRepository: CsraReviewRepository

  // Deleting reviews cascades to csra_next_review (set_by_review_id FK is ON DELETE CASCADE).
  @BeforeEach
  fun setup() {
    reviewRepository.deleteAll()
  }

  private fun nextReview(prisonerNumber: String, date: LocalDate?) {
    val review = reviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = prisonerNumber,
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2026-01-01"),
        type = CsraType.CSRA_INITIAL_REVIEW,
        finalResult = CsraResult.HIGH_GENERAL,
        finalResultDate = LocalDate.parse("2026-01-01"),
        createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
        createdBy = "NQP56Y",
      ),
    )
    repository.saveAndFlush(
      CsraNextReviewEntity(
        prisonerNumber = prisonerNumber,
        nextReviewDate = date,
        setByReviewId = review.id!!,
        updatedAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      ),
    )
  }

  @Test
  fun `finds next reviews for the given prisoners`() {
    nextReview("A1111AA", LocalDate.parse("2026-06-01"))
    nextReview("B2222BB", LocalDate.parse("2026-07-01"))
    nextReview("C3333CC", null)
    repository.flush()

    val found = repository
      .findAllByPrisonerNumberIn(listOf("A1111AA", "B2222BB", "MISSING99"))
      .associateBy { it.prisonerNumber }

    assertThat(found.keys).containsExactlyInAnyOrder("A1111AA", "B2222BB")
    assertThat(found.getValue("A1111AA").nextReviewDate).isEqualTo(LocalDate.parse("2026-06-01"))
    assertThat(found.getValue("B2222BB").nextReviewDate).isEqualTo(LocalDate.parse("2026-07-01"))
  }
}
