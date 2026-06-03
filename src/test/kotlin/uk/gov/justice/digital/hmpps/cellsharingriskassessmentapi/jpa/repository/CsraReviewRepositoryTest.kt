package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.TestBase
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CsraReviewRepositoryTest : TestBase() {

  @Autowired
  lateinit var repository: CsraReviewRepository

  @BeforeEach
  fun setup() {
    repository.deleteAll()
  }

  private fun review(prisonerNumber: String, assessmentDate: LocalDate) = CsraReviewEntity(
    prisonerNumber = prisonerNumber,
    prisonId = "LEI",
    assessmentDate = assessmentDate,
    type = CsraType.RATING,
    finalResult = CsraResult.HIGH,
    finalResultDate = assessmentDate,
    nextReviewDate = assessmentDate.plusMonths(6),
    createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
  )

  @Test
  fun `persists and generates a UUID v7 id`() {
    val saved = repository.saveAndFlush(review("A1234BC", LocalDate.parse("2025-11-22")))

    assertThat(saved.id).isNotNull()
    // v7 UUIDs report version 7
    assertThat(saved.id!!.version()).isEqualTo(7)
  }

  @Test
  fun `round-trips all core fields including enums`() {
    val id = repository.saveAndFlush(review("A1234BC", LocalDate.parse("2025-11-22"))).id!!
    repository.flush()

    val found = repository.findById(id).getOrNull()!!
    assertThat(found.prisonerNumber).isEqualTo("A1234BC")
    assertThat(found.prisonId).isEqualTo("LEI")
    assertThat(found.type).isEqualTo(CsraType.RATING)
    assertThat(found.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(found.finalResultDate).isEqualTo(LocalDate.parse("2025-11-22"))
    assertThat(found.nextReviewDate).isEqualTo(LocalDate.parse("2026-05-22"))
    assertThat(found.createdBy).isEqualTo("NQP56Y")
    assertThat(found.interimResult).isNull()
  }

  @Test
  fun `finds all reviews for a prisoner ordered by assessment date descending`() {
    repository.save(review("A1234BC", LocalDate.parse("2024-01-01")))
    repository.save(review("A1234BC", LocalDate.parse("2025-01-01")))
    repository.save(review("Z9999ZZ", LocalDate.parse("2025-06-01")))
    repository.flush()

    val reviews = repository.findAllByPrisonerNumberOrderByAssessmentDateDesc("A1234BC")

    assertThat(reviews).hasSize(2)
    assertThat(reviews.map { it.assessmentDate })
      .containsExactly(LocalDate.parse("2025-01-01"), LocalDate.parse("2024-01-01"))
  }
}
