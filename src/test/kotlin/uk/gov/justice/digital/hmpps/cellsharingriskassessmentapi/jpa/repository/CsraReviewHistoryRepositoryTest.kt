package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.TestBase
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CsraReviewHistoryRepositoryTest : TestBase() {

  @Autowired
  lateinit var repository: CsraReviewRepository

  @BeforeEach
  fun setup() {
    repository.deleteAll()
  }

  private val pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("assessmentDate"), Sort.Order.desc("id")))

  private fun review(
    prisonerNumber: String = "A1234BC",
    assessmentDate: LocalDate,
    finalResult: CsraResult? = CsraResult.STANDARD,
    interimResult: CsraResult? = null,
    prisonId: String? = "LEI",
  ) = CsraReviewEntity(
    prisonerNumber = prisonerNumber,
    prisonId = prisonId,
    assessmentDate = assessmentDate,
    type = CsraType.REVIEW,
    finalResult = finalResult,
    finalResultDate = finalResult?.let { assessmentDate },
    interimResult = interimResult,
    interimResultDate = interimResult?.let { assessmentDate },
    createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
  )

  private fun spec(
    prisonerNumber: String = "A1234BC",
    from: LocalDate? = null,
    to: LocalDate? = null,
    prisons: List<String>? = null,
    results: List<CsraResult>? = null,
  ) = CsraReviewSpecifications.history(prisonerNumber, from, to, prisons, results)

  @Test
  fun `returns only the prisoner's rated reviews, newest first`() {
    repository.save(review(assessmentDate = LocalDate.parse("2024-01-01")))
    repository.save(review(assessmentDate = LocalDate.parse("2025-01-01")))
    repository.save(review(prisonerNumber = "Z9999ZZ", assessmentDate = LocalDate.parse("2025-06-01")))
    repository.flush()

    val page = repository.findAll(spec(), pageable)

    assertThat(page.content.map { it.assessmentDate })
      .containsExactly(LocalDate.parse("2025-01-01"), LocalDate.parse("2024-01-01"))
  }

  @Test
  fun `orders same-date reviews by newest id first`() {
    val older = repository.saveAndFlush(review(assessmentDate = LocalDate.parse("2025-01-01")))
    val newer = repository.saveAndFlush(review(assessmentDate = LocalDate.parse("2025-01-01")))

    val page = repository.findAll(spec(), pageable)

    assertThat(page.content.map { it.id }).containsExactly(newer.id, older.id)
  }

  @Test
  fun `excludes reviews with no result`() {
    repository.save(review(assessmentDate = LocalDate.parse("2025-01-01")))
    repository.save(review(assessmentDate = LocalDate.parse("2025-02-01"), finalResult = null))
    repository.flush()

    val page = repository.findAll(spec(), pageable)

    assertThat(page.content.map { it.assessmentDate }).containsExactly(LocalDate.parse("2025-01-01"))
  }

  @Test
  fun `keeps interim-only reviews and reports their interim result as the rating`() {
    repository.saveAndFlush(
      review(assessmentDate = LocalDate.parse("2025-03-01"), finalResult = null, interimResult = CsraResult.HIGH_GENERAL),
    )

    val page = repository.findAll(spec(results = listOf(CsraResult.HIGH_GENERAL)), pageable)

    assertThat(page.content).hasSize(1)
  }

  @Test
  fun `paginates`() {
    (1..3).forEach { repository.save(review(assessmentDate = LocalDate.parse("2025-0$it-01"))) }
    repository.flush()

    val firstPage = repository.findAll(spec(), PageRequest.of(0, 2, pageable.sort))

    assertThat(firstPage.totalElements).isEqualTo(3)
    assertThat(firstPage.totalPages).isEqualTo(2)
    assertThat(firstPage.content).hasSize(2)
    assertThat(firstPage.content.map { it.assessmentDate })
      .containsExactly(LocalDate.parse("2025-03-01"), LocalDate.parse("2025-02-01"))
  }

  @Test
  fun `filters by date range inclusively`() {
    repository.save(review(assessmentDate = LocalDate.parse("2024-01-01")))
    repository.save(review(assessmentDate = LocalDate.parse("2025-06-15")))
    repository.save(review(assessmentDate = LocalDate.parse("2026-01-01")))
    repository.flush()

    val page = repository.findAll(
      spec(from = LocalDate.parse("2025-01-01"), to = LocalDate.parse("2025-12-31")),
      pageable,
    )

    assertThat(page.content.map { it.assessmentDate }).containsExactly(LocalDate.parse("2025-06-15"))
  }

  @Test
  fun `filters by establishment`() {
    repository.save(review(assessmentDate = LocalDate.parse("2025-01-01"), prisonId = "LEI"))
    repository.save(review(assessmentDate = LocalDate.parse("2025-02-01"), prisonId = "MDI"))
    repository.save(review(assessmentDate = LocalDate.parse("2025-03-01"), prisonId = "WWI"))
    repository.flush()

    val page = repository.findAll(spec(prisons = listOf("LEI", "WWI")), pageable)

    assertThat(page.content.map { it.prisonId }).containsExactly("WWI", "LEI")
  }

  @Test
  fun `filters by rating results`() {
    repository.save(review(assessmentDate = LocalDate.parse("2025-01-01"), finalResult = CsraResult.STANDARD))
    repository.save(review(assessmentDate = LocalDate.parse("2025-02-01"), finalResult = CsraResult.HIGH))
    repository.save(review(assessmentDate = LocalDate.parse("2025-03-01"), finalResult = CsraResult.HIGH_SPECIFIC))
    repository.flush()

    val page = repository.findAll(
      spec(results = listOf(CsraResult.HIGH, CsraResult.HIGH_GENERAL, CsraResult.HIGH_SPECIFIC)),
      pageable,
    )

    assertThat(page.content.map { it.finalResult }).containsExactly(CsraResult.HIGH_SPECIFIC, CsraResult.HIGH)
  }

  @Test
  fun `summary rows expose the current result and date for every rated review`() {
    repository.save(review(assessmentDate = LocalDate.parse("2025-01-01"), finalResult = CsraResult.STANDARD))
    repository.save(review(assessmentDate = LocalDate.parse("2025-02-01"), finalResult = null, interimResult = CsraResult.HIGH_GENERAL))
    repository.save(review(assessmentDate = LocalDate.parse("2025-03-01"), finalResult = null, interimResult = null))
    repository.flush()

    val rows = repository.findSummaryRows("A1234BC")

    assertThat(rows).hasSize(2)
    assertThat(rows.map { it.result })
      .containsExactlyInAnyOrder(CsraResult.STANDARD, CsraResult.HIGH_GENERAL)
  }
}
