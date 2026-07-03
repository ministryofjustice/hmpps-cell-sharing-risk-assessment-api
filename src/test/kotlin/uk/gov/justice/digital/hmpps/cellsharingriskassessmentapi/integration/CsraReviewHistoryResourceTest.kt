package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
  }

  @Test
  fun `returns the summary and a newest-first page resolving comments from both sources`() {
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
}
