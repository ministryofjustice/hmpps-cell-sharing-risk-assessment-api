package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraReviewHistoryResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    finalResult: CsraResult,
    prisonId: String,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = CsraType.REVIEW,
      finalResult = finalResult,
      finalResultDate = assessmentDate,
      createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
      createdBy = "NQP56Y",
    ),
  )

  private fun withNomisComment(review: CsraReviewEntity, comment: String) {
    csraReviewNomisRepository.saveAndFlush(CsraReviewNomisEntity(csraReview = review, reviewComment = comment))
  }

  private fun withNomis(
    review: CsraReviewEntity,
    calculatedLevel: CsraLevel? = null,
    reviewLevel: CsraLevel? = null,
    approvedLevel: CsraLevel? = null,
    evaluationResultCode: CsraEvaluationResultCode? = null,
    evaluationDate: LocalDate? = null,
    comment: String? = null,
    reviewComment: String? = null,
  ) {
    csraReviewNomisRepository.saveAndFlush(
      CsraReviewNomisEntity(
        csraReview = review,
        status = CsraStatus.A,
        calculatedLevel = calculatedLevel,
        reviewLevel = reviewLevel,
        approvedLevel = approvedLevel,
        evaluationResultCode = evaluationResultCode,
        evaluationDate = evaluationDate,
        comment = comment,
        reviewComment = reviewComment,
      ),
    )
  }

  private fun withFinalStageComment(review: CsraReviewEntity, comment: String) {
    csraAssessmentStageRepository.saveAndFlush(
      CsraAssessmentStageEntity(csraReview = review, stage = CsraAssessmentStage.FINAL, assessmentComment = comment),
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/history")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/history")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns an empty history with a zeroed summary when the prisoner has no CSRAs`() {
    webTestClient.get().uri("/csra-review/prisoner/E0000EE/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
      .jsonPath("$.content").isEmpty
      .jsonPath("$.summary.totalCsras").isEqualTo(0)
      .jsonPath("$.summary.highCount").isEqualTo(0)
      .jsonPath("$.summary.standardCount").isEqualTo(0)
      .jsonPath("$.summary.firstAssessmentDate").doesNotExist()
      .jsonPath("$.summary.lastHighDate").doesNotExist()
      .jsonPath("$.summary.establishments").isEmpty
  }

  @Test
  fun `returns the summary and a newest-first page resolving comments from both sources`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)", "MDI" to "Moorland (HMP)"))
    val legacyHigh = review("H1111HH", LocalDate.parse("2023-07-14"), CsraResult.HIGH, "LEI")
    withNomisComment(legacyHigh, "Legacy high comment")
    val standard = review("H1111HH", LocalDate.parse("2025-06-30"), CsraResult.STANDARD, "LEI")
    withFinalStageComment(standard, "PNC checked. No issues found.")
    val highSpecific = review("H1111HH", LocalDate.parse("2025-10-11"), CsraResult.HIGH_SPECIFIC, "MDI")
    withFinalStageComment(highSpecific, "History of racist incidents.")

    webTestClient.get().uri("/csra-review/prisoner/H1111HH/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.summary.totalCsras").isEqualTo(3)
      .jsonPath("$.summary.highCount").isEqualTo(2)
      .jsonPath("$.summary.standardCount").isEqualTo(1)
      .jsonPath("$.summary.firstAssessmentDate").isEqualTo("2023-07-14")
      .jsonPath("$.summary.lastAssessmentDate").isEqualTo("2025-10-11")
      .jsonPath("$.summary.lastHighDate").isEqualTo("2025-10-11")
      .jsonPath("$.summary.establishments.length()").isEqualTo(2)
      .jsonPath("$.summary.establishments[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.summary.establishments[0].prisonName").isEqualTo("Leeds (HMP)")
      .jsonPath("$.summary.establishments[1].prisonId").isEqualTo("MDI")
      .jsonPath("$.summary.establishments[1].prisonName").isEqualTo("Moorland (HMP)")
      .jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.content.length()").isEqualTo(3)
      .jsonPath("$.content[0].rating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.content[0].reviewComment").isEqualTo("History of racist incidents.")
      .jsonPath("$.content[0].prisonId").isEqualTo("MDI")
      .jsonPath("$.content[0].recordedDate").isEqualTo("2025-10-11")
      .jsonPath("$.content[1].rating").isEqualTo("STANDARD")
      .jsonPath("$.content[1].reviewComment").isEqualTo("PNC checked. No issues found.")
      .jsonPath("$.content[2].rating").isEqualTo("HIGH")
      .jsonPath("$.content[2].reviewComment").isEqualTo("Legacy high comment")
  }

  @Test
  fun `a legacy LOW review keeps its raw level while still rating as standard`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val low = review("L1111LL", LocalDate.parse("2010-03-13"), CsraResult.STANDARD, "LEI")
    withNomis(
      low,
      calculatedLevel = CsraLevel.LOW,
      comment = "Assessment comment",
      reviewComment = "Approval comment",
      evaluationResultCode = CsraEvaluationResultCode.APP,
      evaluationDate = LocalDate.parse("2010-03-20"),
    )

    webTestClient.get().uri("/csra-review/prisoner/L1111LL/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // The rating the service reasons about is unchanged; the raw level is what the screen renders.
      .jsonPath("$.content[0].rating").isEqualTo("STANDARD")
      .jsonPath("$.content[0].prisonName").isEqualTo("Leeds (HMP)")
      .jsonPath("$.content[0].legacy.level").isEqualTo("LOW")
      .jsonPath("$.content[0].legacy.assessmentComment").isEqualTo("Assessment comment")
      .jsonPath("$.content[0].legacy.assessmentDate").isEqualTo("2010-03-13")
      .jsonPath("$.content[0].legacy.approvalComment").isEqualTo("Approval comment")
      .jsonPath("$.content[0].legacy.approvalStatus").isEqualTo("APPROVED")
      .jsonPath("$.content[0].legacy.approvalDate").isEqualTo("2010-03-20")
  }

  @Test
  fun `a legacy MED review keeps its raw level`() {
    val med = review("M1111MM", LocalDate.parse("2009-09-29"), CsraResult.STANDARD, "LEI")
    withNomis(med, calculatedLevel = CsraLevel.MED)

    webTestClient.get().uri("/csra-review/prisoner/M1111MM/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].rating").isEqualTo("STANDARD")
      .jsonPath("$.content[0].legacy.level").isEqualTo("MED")
  }

  @Test
  fun `a legacy review whose level changed at approval reports it, with both dates`() {
    val changed = review("C1111CC", LocalDate.parse("2012-05-23"), CsraResult.HIGH, "LEI")
    withNomis(
      changed,
      calculatedLevel = CsraLevel.STANDARD,
      approvedLevel = CsraLevel.HI,
      evaluationResultCode = CsraEvaluationResultCode.APP,
      evaluationDate = LocalDate.parse("2012-06-01"),
    )

    webTestClient.get().uri("/csra-review/prisoner/C1111CC/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].legacy.approvalStatus").isEqualTo("LEVEL_CHANGED_AT_APPROVAL")
      .jsonPath("$.content[0].legacy.level").isEqualTo("HI")
      .jsonPath("$.content[0].legacy.assessmentDate").isEqualTo("2012-05-23")
      .jsonPath("$.content[0].legacy.approvalDate").isEqualTo("2012-06-01")
  }

  @Test
  fun `a legacy review that never went through approval carries no approval status`() {
    // The prod-typical row: no NOMIS review carries approval data, so the screen shows no badge.
    val plain = review("N1111NN", LocalDate.parse("2011-10-24"), CsraResult.STANDARD, "LEI")
    withNomis(plain, calculatedLevel = CsraLevel.STANDARD, comment = "Assessment comment")

    webTestClient.get().uri("/csra-review/prisoner/N1111NN/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].legacy.level").isEqualTo("STANDARD")
      .jsonPath("$.content[0].legacy.approvalStatus").doesNotExist()
      .jsonPath("$.content[0].legacy.approvalDate").doesNotExist()
      .jsonPath("$.content[0].legacy.approvalComment").doesNotExist()
  }

  @Test
  fun `a new-model review carries no legacy block at all`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val dps = review("D1111DD", LocalDate.parse("2025-10-11"), CsraResult.HIGH_GENERAL, "LEI")
    withFinalStageComment(dps, "Day 2 assessment complete.")

    webTestClient.get().uri("/csra-review/prisoner/D1111DD/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].legacy").doesNotExist()
      .jsonPath("$.content[0].reviewComment").isEqualTo("Day 2 assessment complete.")
      .jsonPath("$.content[0].prisonName").isEqualTo("Leeds (HMP)")
  }

  @Test
  fun `legacy LOW and MED rows still filter as standard`() {
    val low = review("B1111BB", LocalDate.parse("2010-03-13"), CsraResult.STANDARD, "LEI")
    withNomis(low, calculatedLevel = CsraLevel.LOW)
    val med = review("B1111BB", LocalDate.parse("2009-09-29"), CsraResult.STANDARD, "LEI")
    withNomis(med, calculatedLevel = CsraLevel.MED)

    webTestClient.get().uri("/csra-review/prisoner/B1111BB/history?ratings=STANDARD")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)

    webTestClient.get().uri("/csra-review/prisoner/B1111BB/history?ratings=HIGH")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
  }

  @Test
  fun `filters the list by rating bucket while keeping the whole-history summary`() {
    review("F2222FF", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    review("F2222FF", LocalDate.parse("2024-06-01"), CsraResult.HIGH, "LEI")
    review("F2222FF", LocalDate.parse("2025-01-01"), CsraResult.HIGH_GENERAL, "MDI")

    webTestClient.get().uri("/csra-review/prisoner/F2222FF/history?ratings=HIGH")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)
      .jsonPath("$.content.length()").isEqualTo(2)
      .jsonPath("$.content[0].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[1].rating").isEqualTo("HIGH")
      .jsonPath("$.summary.totalCsras").isEqualTo(3)
      .jsonPath("$.summary.highCount").isEqualTo(2)
      .jsonPath("$.summary.standardCount").isEqualTo(1)
  }

  @Test
  fun `filters the list by establishment and date range`() {
    review("D3333DD", LocalDate.parse("2023-05-01"), CsraResult.STANDARD, "LEI")
    review("D3333DD", LocalDate.parse("2025-05-01"), CsraResult.STANDARD, "MDI")
    review("D3333DD", LocalDate.parse("2025-09-01"), CsraResult.STANDARD, "LEI")

    webTestClient.get().uri("/csra-review/prisoner/D3333DD/history?establishments=LEI&fromDate=2025-01-01")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].recordedDate").isEqualTo("2025-09-01")
      .jsonPath("$.content[0].prisonId").isEqualTo("LEI")
  }

  @Test
  fun `establishment list resolves names from prison-register and falls back to the id when unknown`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    review("G4444GG", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    review("G4444GG", LocalDate.parse("2025-01-01"), CsraResult.STANDARD, "MDI")

    webTestClient.get().uri("/csra-review/prisoner/G4444GG/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // Name-sorted: "Leeds (HMP)" before the unresolved "MDI".
      .jsonPath("$.summary.establishments.length()").isEqualTo(2)
      .jsonPath("$.summary.establishments[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.summary.establishments[0].prisonName").isEqualTo("Leeds (HMP)")
      .jsonPath("$.summary.establishments[1].prisonId").isEqualTo("MDI")
      .jsonPath("$.summary.establishments[1].prisonName").isEqualTo("MDI")
  }
}
