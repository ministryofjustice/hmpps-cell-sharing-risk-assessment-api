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
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
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
    createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
  )

  private fun ratedReview(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    interimResult: CsraResult? = null,
    finalResult: CsraResult? = null,
  ) = CsraReviewEntity(
    prisonerNumber = prisonerNumber,
    prisonId = "LEI",
    assessmentDate = assessmentDate,
    type = CsraType.CSRA_INITIAL_REVIEW,
    interimResult = interimResult,
    interimResultDate = interimResult?.let { assessmentDate },
    finalResult = finalResult,
    finalResultDate = finalResult?.let { assessmentDate },
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

  @Test
  fun `counts current ratings per prisoner using the latest review, bucketing null for in-progress`() {
    // High: final HIGH, provisional HIGH_GENERAL, final HIGH_SPECIFIC
    repository.save(ratedReview("H0001AA", LocalDate.parse("2026-03-01"), finalResult = CsraResult.HIGH))
    repository.save(ratedReview("H0002AA", LocalDate.parse("2026-03-01"), interimResult = CsraResult.HIGH_GENERAL))
    repository.save(ratedReview("H0003AA", LocalDate.parse("2026-03-01"), finalResult = CsraResult.HIGH_SPECIFIC))
    // Standard
    repository.save(ratedReview("S0001AA", LocalDate.parse("2026-03-01"), finalResult = CsraResult.STANDARD))
    // In progress -> current rating null
    repository.save(ratedReview("N0001AA", LocalDate.parse("2026-03-01")))
    // Latest review wins: an old HIGH superseded by a newer STANDARD
    repository.save(ratedReview("L0001AA", LocalDate.parse("2024-01-01"), finalResult = CsraResult.HIGH))
    repository.save(ratedReview("L0001AA", LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD))
    repository.flush()

    val counts = repository.countCurrentRatingsByPrisonerNumberIn(
      listOf("H0001AA", "H0002AA", "H0003AA", "S0001AA", "N0001AA", "L0001AA", "MISSING99"),
    ).associate { it.currentResult to it.count }

    assertThat(counts[CsraResult.HIGH.name]).isEqualTo(1)
    assertThat(counts[CsraResult.HIGH_GENERAL.name]).isEqualTo(1)
    assertThat(counts[CsraResult.HIGH_SPECIFIC.name]).isEqualTo(1)
    assertThat(counts[CsraResult.STANDARD.name]).isEqualTo(2) // S0001AA + L0001AA (latest STANDARD)
    assertThat(counts[null]).isEqualTo(1) // N0001AA in progress
  }

  @Test
  fun `finds the latest review per prisoner with its projected fields`() {
    repository.save(ratedReview("R0001AA", LocalDate.parse("2024-01-01"), finalResult = CsraResult.HIGH))
    repository.save(ratedReview("R0001AA", LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD)) // latest
    repository.save(ratedReview("R0002AA", LocalDate.parse("2026-03-01"), interimResult = CsraResult.HIGH_GENERAL)) // provisional
    repository.save(ratedReview("R0003AA", LocalDate.parse("2026-03-01"))) // in progress, no rating
    repository.flush()

    val rows = repository
      .findCurrentReviewsByPrisonerNumberIn(listOf("R0001AA", "R0002AA", "R0003AA", "MISSING99"))
      .associateBy { it.prisonerNumber }

    assertThat(rows).hasSize(3)

    val latest = rows.getValue("R0001AA")
    assertThat(latest.finalResult).isEqualTo(CsraResult.STANDARD.name)
    assertThat(latest.interimResult).isNull()
    assertThat(latest.type).isEqualTo(CsraType.CSRA_INITIAL_REVIEW.name)
    assertThat(latest.assessmentDate).isEqualTo(LocalDate.parse("2026-02-01"))
    assertThat(latest.finalResultDate).isEqualTo(LocalDate.parse("2026-02-01"))

    val provisional = rows.getValue("R0002AA")
    assertThat(provisional.finalResult).isNull()
    assertThat(provisional.interimResult).isEqualTo(CsraResult.HIGH_GENERAL.name)

    val inProgress = rows.getValue("R0003AA")
    assertThat(inProgress.finalResult).isNull()
    assertThat(inProgress.interimResult).isNull()
  }

  @Test
  fun `finds in-progress reviews of a type at a prison, excluding completed, other types and other prisons`() {
    fun entity(prisonerNumber: String, type: CsraType, finalResult: CsraResult?, prisonId: String) = CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = LocalDate.parse("2026-07-01"),
      type = type,
      finalResult = finalResult,
      finalResultDate = finalResult?.let { LocalDate.parse("2026-07-01") },
      createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
      createdBy = "NQP56Y",
    )
    repository.save(entity("INPROG", CsraType.CSRA_INITIAL_REVIEW, null, "LEI")) // match
    repository.save(entity("DONE", CsraType.CSRA_INITIAL_REVIEW, CsraResult.STANDARD, "LEI")) // completed
    repository.save(entity("REVIEW", CsraType.CSRA_REVIEW, null, "LEI")) // wrong type
    repository.save(entity("OTHERP", CsraType.CSRA_INITIAL_REVIEW, null, "MDI")) // wrong prison
    repository.save(entity("LEGACY", CsraType.RATING, null, "LEI")) // legacy null-result, wrong type
    repository.save(entity("CLOSED", CsraType.CSRA_INITIAL_REVIEW, null, "LEI").apply { status = CsraReviewStatus.CLOSED }) // no longer in progress
    repository.flush()

    val found = repository.findAllByPrisonIdAndTypeAndFinalResultIsNullAndStatus("LEI", CsraType.CSRA_INITIAL_REVIEW, CsraReviewStatus.IN_PROGRESS)

    assertThat(found.map { it.prisonerNumber }).containsExactly("INPROG")
  }
}
