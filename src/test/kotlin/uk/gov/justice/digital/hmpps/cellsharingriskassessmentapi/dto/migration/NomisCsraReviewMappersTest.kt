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
    overrideLevel: CsraLevel? = null,
    reviewLevel: CsraLevel? = null,
    approvedLevel: CsraLevel? = null,
    evaluationDate: LocalDate? = null,
    assessmentPrisonId: String? = "LEI",
    status: CsraStatus = CsraStatus.A,
  ) = NomisCsraReview(
    assessmentPrisonId = assessmentPrisonId,
    assessmentDate = LocalDate.parse("2025-11-22"),
    assessmentType = assessmentType,
    calculatedLevel = calculatedLevel,
    overrideLevel = overrideLevel,
    reviewLevel = reviewLevel,
    approvedLevel = approvedLevel,
    evaluationDate = evaluationDate,
    score = BigDecimal("1000"),
    status = status,
    nextReviewDate = LocalDate.parse("2026-05-22"),
    createdDateTime = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
    bookingId = 1234567,
    nomisSequence = 1,
  )

  @ParameterizedTest
  @CsvSource("CSRF,FULL", "CSRH,HEALTH", "CSRDO,LOCATE", "CSR,RATING", "CSR1,RECEPTION", "CSRREV,NOMIS_REVIEW")
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
  fun `a review level that is not PEND is the final rating, whatever the other levels`() {
    // A review that lowered a high score: NOMIS and prison-api show STANDARD, not HIGH.
    val entity = review(reviewLevel = CsraLevel.STANDARD, overrideLevel = CsraLevel.HI, calculatedLevel = CsraLevel.HI)
      .toNewCsraReview("A1234BC")

    assertThat(entity.finalResult).isEqualTo(CsraResult.STANDARD)
    assertThat(entity.interimResult).isNull()
  }

  @Test
  fun `the approved level plays no part in the rating`() {
    // APPROVED_SUP_LEVEL_TYPE is null on every known CSRA row and prison-api does not map it.
    val entity = review(approvedLevel = CsraLevel.HI, calculatedLevel = CsraLevel.STANDARD).toNewCsraReview("A1234BC")

    assertThat(entity.finalResult).isEqualTo(CsraResult.STANDARD)
  }

  @Test
  fun `a level that was never approved is still a final rating`() {
    // Approval was a governance step, not a completion step, and in practice almost no NOMIS review
    // carries an approved level — treating those as provisional made every migrated review provisional.
    val entity = review(approvedLevel = null, reviewLevel = CsraLevel.HI).toNewCsraReview("A1234BC")

    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.finalResultDate).isEqualTo(LocalDate.parse("2025-11-22"))
    assertThat(entity.interimResult).isNull()
    assertThat(entity.interimResultDate).isNull()
  }

  @Test
  fun `a review NOMIS still holds as provisional is the only provisional case`() {
    val entity = review(reviewLevel = CsraLevel.HI, status = CsraStatus.P).toNewCsraReview("A1234BC")

    assertThat(entity.interimResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.finalResult).isNull()
  }

  @Test
  fun `a provisional review with only a calculated level is also provisional`() {
    val entity = review(calculatedLevel = CsraLevel.HI, status = CsraStatus.P).toNewCsraReview("A1234BC")

    assertThat(entity.interimResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.finalResult).isNull()
  }

  @Test
  fun `a pending review level does not hide a rating on the other levels`() {
    val entity = review(reviewLevel = CsraLevel.PEND, calculatedLevel = CsraLevel.HI).toNewCsraReview("A1234BC")

    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
  }

  @ParameterizedTest
  @CsvSource(
    nullValues = ["null"],
    value = [
      // the review level wins outright — a calculated or override level never beats it
      "STANDARD,HI,null,STANDARD",
      "STANDARD,null,HI,STANDARD",
      "LOW,HI,HI,STANDARD",
      "HI,LOW,LOW,HIGH",
      // with no review level, the stronger of the override and calculated levels wins
      "null,STANDARD,HI,HIGH",
      "null,HI,STANDARD,HIGH",
      "PEND,STANDARD,HI,HIGH",
      // PEND can never win a head-to-head
      "null,PEND,HI,HIGH",
      "null,HI,PEND,HIGH",
    ],
  )
  fun `resolves the rating the way prison-api does`(
    reviewLevel: CsraLevel?,
    calculatedLevel: CsraLevel?,
    overrideLevel: CsraLevel?,
    expected: CsraResult,
  ) {
    val review = review(reviewLevel = reviewLevel, calculatedLevel = calculatedLevel, overrideLevel = overrideLevel)

    assertThat(review.toNewCsraReview("A1234BC").finalResult).isEqualTo(expected)
  }

  @ParameterizedTest
  @CsvSource(
    nullValues = ["null"],
    value = [
      // The level itself, not the result — LOW and MED both resolve to STANDARD, so the table above
      // cannot tell them apart, and the history screen needs exactly that distinction.
      "LOW,null,null,LOW",
      "MED,null,null,MED",
      "MED,LOW,null,MED",
      "LOW,MED,null,MED",
      "HI,LOW,null,HI",
      // a review level that is not PEND wins outright, even over a stronger level
      "HI,null,LOW,LOW",
      "HI,HI,MED,MED",
      // an override alone is shown, even PEND; both PEND falls through to the override's PEND
      "null,MED,null,MED",
      "null,PEND,null,PEND",
      "PEND,PEND,null,PEND",
      // a PEND review level does not hide the other levels, and alone is no level
      "HI,null,PEND,HI",
      "null,null,PEND,null",
      "PEND,null,null,null",
      "null,null,null,null",
    ],
  )
  fun `resolves which NOMIS level wins`(
    calculatedLevel: CsraLevel?,
    overrideLevel: CsraLevel?,
    reviewLevel: CsraLevel?,
    expected: CsraLevel?,
  ) {
    assertThat(resolveNomisLevel(calculatedLevel = calculatedLevel, overrideLevel = overrideLevel, reviewLevel = reviewLevel))
      .isEqualTo(expected)
  }

  @Test
  fun `a calculated level alone is a rating unless it is PEND`() {
    assertThat(review(calculatedLevel = CsraLevel.HI).toNewCsraReview("A1234BC").finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(review(calculatedLevel = CsraLevel.PEND).toNewCsraReview("A1234BC").finalResult).isNull()
  }

  @Test
  fun `a review with no levels at all has no rating`() {
    val entity = review().toNewCsraReview("A1234BC")

    assertThat(entity.finalResult).isNull()
    assertThat(entity.interimResult).isNull()
  }

  @Test
  fun `maps core fields and leaves interim result unset for an approved review`() {
    val entity = review(reviewLevel = CsraLevel.HI, evaluationDate = LocalDate.parse("2025-12-08")).toNewCsraReview("A1234BC")

    assertThat(entity.prisonerNumber).isEqualTo("A1234BC")
    assertThat(entity.prisonId).isEqualTo("LEI")
    assertThat(entity.assessmentDate).isEqualTo(LocalDate.parse("2025-11-22"))
    assertThat(entity.type).isEqualTo(CsraType.RATING)
    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.finalResultDate).isEqualTo(LocalDate.parse("2025-12-08"))
    assertThat(entity.createdAt).isEqualTo(LocalDateTime.parse("2025-12-06T12:34:56"))
    assertThat(entity.createdBy).isEqualTo("NQP56Y")
    assertThat(entity.interimResult).isNull()
    assertThat(entity.interimResultDate).isNull()
    assertThat(entity.lastModifiedAt).isNull()
    assertThat(entity.lastModifiedBy).isNull()
  }

  @Test
  fun `final result date falls back to assessment date when no evaluation date`() {
    val entity = review(reviewLevel = CsraLevel.STANDARD, evaluationDate = null).toNewCsraReview("A1234BC")
    assertThat(entity.finalResultDate).isEqualTo(LocalDate.parse("2025-11-22"))
  }

  @Test
  fun `no result means no result date`() {
    val entity = review(reviewLevel = CsraLevel.PEND).toNewCsraReview("A1234BC")
    assertThat(entity.finalResult).isNull()
    assertThat(entity.finalResultDate).isNull()
  }

  @Test
  fun `update from NOMIS overwrites core fields and stamps last modified`() {
    val entity = review(reviewLevel = CsraLevel.STANDARD).toNewCsraReview("A1234BC")

    entity.updateFromNomis("A1234BC", review(reviewLevel = CsraLevel.HI, assessmentPrisonId = "MDI"), clock)

    assertThat(entity.finalResult).isEqualTo(CsraResult.HIGH)
    assertThat(entity.prisonId).isEqualTo("MDI")
    assertThat(entity.lastModifiedBy).isEqualTo("NQP56Y")
    assertThat(entity.lastModifiedAt).isEqualTo(LocalDateTime.now(clock))
  }

  @Test
  fun `a review leaving NOMIS provisional status clears the interim result`() {
    val entity = review(reviewLevel = CsraLevel.HI, status = CsraStatus.P).toNewCsraReview("A1234BC")
    assertThat(entity.interimResult).isEqualTo(CsraResult.HIGH)

    entity.updateFromNomis("A1234BC", review(reviewLevel = CsraLevel.STANDARD, status = CsraStatus.A), clock)

    assertThat(entity.finalResult).isEqualTo(CsraResult.STANDARD)
    assertThat(entity.interimResult).isNull()
    assertThat(entity.interimResultDate).isNull()
  }

  private val reviewDetails = listOf(
    CsraReviewDetailDto(
      code = "SECTION1",
      description = "First section",
      questions = listOf(
        CsraQuestionDto(
          code = "Q1",
          description = "Question one",
          responses = listOf(CsraResponseDto(code = "R1", answer = "Yes", comment = "ok")),
        ),
      ),
    ),
  )

  private fun fullReview() = NomisCsraReview(
    bookingId = 1234567,
    nomisSequence = 1,
    assessmentPrisonId = "LEI",
    assessmentDate = LocalDate.parse("2025-11-22"),
    assessmentType = CsraAssessmentType.CSR,
    calculatedLevel = CsraLevel.MED,
    overrideLevel = CsraLevel.LOW,
    score = BigDecimal("1000"),
    status = CsraStatus.A,
    committeeCode = CsraCommitteeCode.REVIEW,
    nextReviewDate = LocalDate.parse("2026-05-22"),
    comment = "assessment comment",
    placementPrisonId = "MDI",
    createdDateTime = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
    reviewLevel = CsraLevel.STANDARD,
    approvedLevel = CsraLevel.HI,
    evaluationDate = LocalDate.parse("2025-12-08"),
    evaluationResultCode = CsraEvaluationResultCode.APP,
    reviewCommitteeCode = CsraCommitteeCode.GOV,
    reviewCommitteeComment = "approval comment",
    reviewPlacementPrisonId = "WWI",
    reviewComment = "review comment",
    reviewDetails = reviewDetails,
  )

  @Test
  fun `maps every additional NOMIS field verbatim onto the adjacent record`() {
    val review = fullReview()
    val core = review.toNewCsraReview("A1234BC")

    val nomis = review.toNomisEntity(core, clock)

    assertThat(nomis.csraReview).isSameAs(core)
    // Our clock, not NOMIS's — createdAt on the core record is NOMIS's own timestamp.
    assertThat(nomis.ingestedAt).isEqualTo(LocalDateTime.now(clock))
    assertThat(nomis.score).isEqualByComparingTo(BigDecimal("1000"))
    assertThat(nomis.status).isEqualTo(CsraStatus.A)
    assertThat(nomis.calculatedLevel).isEqualTo(CsraLevel.MED)
    assertThat(nomis.overrideLevel).isEqualTo(CsraLevel.LOW)
    assertThat(nomis.reviewLevel).isEqualTo(CsraLevel.STANDARD)
    assertThat(nomis.approvedLevel).isEqualTo(CsraLevel.HI)
    assertThat(nomis.committeeCode).isEqualTo(CsraCommitteeCode.REVIEW)
    assertThat(nomis.reviewCommitteeCode).isEqualTo(CsraCommitteeCode.GOV)
    assertThat(nomis.evaluationDate).isEqualTo(LocalDate.parse("2025-12-08"))
    assertThat(nomis.evaluationResultCode).isEqualTo(CsraEvaluationResultCode.APP)
    assertThat(nomis.comment).isEqualTo("assessment comment")
    assertThat(nomis.reviewComment).isEqualTo("review comment")
    assertThat(nomis.reviewCommitteeComment).isEqualTo("approval comment")
    assertThat(nomis.placementPrisonId).isEqualTo("MDI")
    assertThat(nomis.reviewPlacementPrisonId).isEqualTo("WWI")
    assertThat(nomis.reviewDetails).isEqualTo(reviewDetails)
    // Kept per review as well as in csra_next_review, which only holds the prisoner's current date.
    assertThat(nomis.nextReviewDate).isEqualTo(LocalDate.parse("2026-05-22"))
  }

  @Test
  fun `update of the adjacent record overwrites every additional field`() {
    val original = fullReview()
    val nomis = original.toNomisEntity(original.toNewCsraReview("A1234BC"), clock)
    nomis.ingestedAt = null

    nomis.updateFromNomis(
      fullReview().copy(
        approvedLevel = CsraLevel.STANDARD,
        overrideLevel = CsraLevel.HI,
        status = CsraStatus.I,
        comment = "changed",
        reviewDetails = emptyList(),
        nextReviewDate = null,
      ),
      clock,
    )

    // Re-stamped on every write, so the column always reflects the most recent load.
    assertThat(nomis.ingestedAt).isEqualTo(LocalDateTime.now(clock))

    assertThat(nomis.approvedLevel).isEqualTo(CsraLevel.STANDARD)
    assertThat(nomis.overrideLevel).isEqualTo(CsraLevel.HI)
    assertThat(nomis.status).isEqualTo(CsraStatus.I)
    assertThat(nomis.comment).isEqualTo("changed")
    assertThat(nomis.reviewDetails).isEmpty()
    // Cleared as well as set — a review whose next review date was removed in NOMIS must not keep the old one.
    assertThat(nomis.nextReviewDate).isNull()
  }
}
