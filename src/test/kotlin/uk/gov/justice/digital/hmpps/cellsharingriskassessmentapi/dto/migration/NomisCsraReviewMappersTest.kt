package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class NomisCsraReviewMappersTest {

  private val clock = Clock.fixed(Instant.parse("2026-01-02T10:00:00Z"), ZoneId.of("Europe/London"))

  private fun review(
    assessmentType: CsraAssessmentType = CsraAssessmentType.CSR,
    calculatedLevel: CsraLevel? = null,
    reviewLevel: CsraLevel? = null,
    approvedLevel: CsraLevel? = null,
    evaluationDate: LocalDate? = null,
    assessmentPrisonId: String? = "LEI",
  ) = NomisCsraReview(
    assessmentPrisonId = assessmentPrisonId,
    assessmentDate = LocalDate.parse("2025-11-22"),
    assessmentType = assessmentType,
    calculatedLevel = calculatedLevel,
    reviewLevel = reviewLevel,
    approvedLevel = approvedLevel,
    evaluationDate = evaluationDate,
    score = BigDecimal("1000"),
    status = CsraStatus.A,
    nextReviewDate = LocalDate.parse("2026-05-22"),
    createdDateTime = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
    bookingId = 1234567,
    nomisSequence = 1,
  )

  @ParameterizedTest
  @CsvSource("CSRF,FULL", "CSRH,HEALTH", "CSRDO,LOCATE", "CSR,RATING", "CSR1,RECEPTION", "CSRREV,REVIEW")
  fun `maps every NOMIS assessment type to a clean type`(nomis: CsraAssessmentType, expected: CsraType) {
    assertThat(nomis.toCsraType()).isEqualTo(expected)
  }

  @ParameterizedTest
  @CsvSource("HI,HIGH", "STANDARD,STANDARD", "LOW,STANDARD", "MED,STANDARD")
  fun `maps NOMIS levels to results`(level: CsraLevel, expected: CsraResult) {
    assertThat(level.toCsraResult()).isEqualTo(expected)
  }

  @Test
  fun `PEND and null levels map to no result`() {
    assertThat(CsraLevel.PEND.toCsraResult()).isNull()
    assertThat((null as CsraLevel?).toCsraResult()).isNull()
  }

  @Test
  fun `final result prefers approved, then review, then calculated level`() {
    assertThat(review(approvedLevel = CsraLevel.HI, reviewLevel = CsraLevel.STANDARD, calculatedLevel = CsraLevel.STANDARD).toNewCsraReview("A1234BC").finalResult)
      .isEqualTo(CsraResult.HIGH)
    assertThat(review(approvedLevel = null, reviewLevel = CsraLevel.HI, calculatedLevel = CsraLevel.STANDARD).toNewCsraReview("A1234BC").finalResult)
      .isEqualTo(CsraResult.HIGH)
    assertThat(review(approvedLevel = null, reviewLevel = null, calculatedLevel = CsraLevel.HI).toNewCsraReview("A1234BC").finalResult)
      .isEqualTo(CsraResult.HIGH)
  }

  @Test
  fun `maps core fields and leaves interim result unset for migrated reviews`() {
    val entity = review(approvedLevel = CsraLevel.HI, evaluationDate = LocalDate.parse("2025-12-08")).toNewCsraReview("A1234BC")

    assertThat(entity.prisonerNumber).isEqualTo("A1234BC")
    assertThat(entity.prisonId).isEqualTo("LEI")
    assertThat(entity.assessmentDate).isEqualTo(LocalDate.parse("2025-11-22"))
    assertThat(entity.type).isEqualTo(CsraType.RATING)
    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.finalResultDate).isEqualTo(LocalDate.parse("2025-12-08"))
    assertThat(entity.nextReviewDate).isEqualTo(LocalDate.parse("2026-05-22"))
    assertThat(entity.createdAt).isEqualTo(LocalDateTime.parse("2025-12-06T12:34:56"))
    assertThat(entity.createdBy).isEqualTo("NQP56Y")
    assertThat(entity.interimResult).isNull()
    assertThat(entity.interimResultDate).isNull()
    assertThat(entity.lastModifiedAt).isNull()
    assertThat(entity.lastModifiedBy).isNull()
  }

  @Test
  fun `final result date falls back to assessment date when no evaluation date`() {
    val entity = review(approvedLevel = CsraLevel.STANDARD, evaluationDate = null).toNewCsraReview("A1234BC")
    assertThat(entity.finalResultDate).isEqualTo(LocalDate.parse("2025-11-22"))
  }

  @Test
  fun `no result means no result date`() {
    val entity = review(approvedLevel = CsraLevel.PEND).toNewCsraReview("A1234BC")
    assertThat(entity.finalResult).isNull()
    assertThat(entity.finalResultDate).isNull()
  }

  @Test
  fun `update from NOMIS overwrites core fields and stamps last modified`() {
    val entity = review(approvedLevel = CsraLevel.STANDARD).toNewCsraReview("A1234BC")

    entity.updateFromNomis("A1234BC", review(approvedLevel = CsraLevel.HI, assessmentPrisonId = "MDI"), clock)

    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.prisonId).isEqualTo("MDI")
    assertThat(entity.lastModifiedBy).isEqualTo("NQP56Y")
    assertThat(entity.lastModifiedAt).isEqualTo(LocalDateTime.now(clock))
  }
}
