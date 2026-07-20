package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraCurrentRatingResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType = CsraType.CSRA_INITIAL_REVIEW,
    interimResult: CsraResult? = null,
    interimResultDate: LocalDate? = null,
    finalResult: CsraResult? = null,
    finalResultDate: LocalDate? = null,
    prisonId: String? = null,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      interimResult = interimResult,
      interimResultDate = interimResultDate,
      finalResult = finalResult,
      finalResultDate = finalResultDate,
      createdAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      createdBy = "NQP56Y",
    ),
  ).also { refreshCurrentRating(it.prisonerNumber) }

  private fun stage(
    review: CsraReviewEntity,
    stage: CsraAssessmentStage,
    completedAt: LocalDateTime,
    prisonId: String = "LEI",
    comment: String? = null,
    build: CsraAssessmentStageEntity.() -> Unit = {},
  ) {
    val entity = CsraAssessmentStageEntity(
      csraReview = review,
      stage = stage,
      completedAt = completedAt,
      completedBy = "NQP56Y",
      prisonId = prisonId,
      assessmentComment = comment,
    ).apply(build)
    csraAssessmentStageRepository.saveAndFlush(entity)
  }

  private fun nextReview(review: CsraReviewEntity, prisonerNumber: String, date: LocalDate) {
    csraNextReviewRepository.saveAndFlush(
      CsraNextReviewEntity(
        prisonerNumber = prisonerNumber,
        nextReviewDate = date,
        setByReviewId = review.id!!,
        updatedAt = LocalDateTime.parse("2026-01-02T09:00:00"),
      ),
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/current-rating")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/current-rating")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns NO_RATING when the prisoner has no CSRA`() {
    webTestClient.get().uri("/csra-review/prisoner/N0000NN/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("NO_RATING")
      .jsonPath("$.rating").isEmpty
      .jsonPath("$.reviewId").isEmpty
      .jsonPath("$.provisional").isEqualTo(false)
  }

  @Test
  fun `returns a complete migrated legacy rating with its NOMIS comment and next review date`() {
    val legacy = review(
      prisonerNumber = "L1111LL",
      assessmentDate = LocalDate.parse("2023-07-14"),
      type = CsraType.REVIEW,
      finalResult = CsraResult.HIGH,
      finalResultDate = LocalDate.parse("2023-07-20"),
      prisonId = "LEI",
    )
    csraReviewNomisRepository.saveAndFlush(CsraReviewNomisEntity(csraReview = legacy, reviewComment = "Legacy high comment"))
    nextReview(legacy, "L1111LL", LocalDate.parse("2024-01-14"))

    webTestClient.get().uri("/csra-review/prisoner/L1111LL/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("HIGH")
      .jsonPath("$.provisional").isEqualTo(false)
      .jsonPath("$.prisonId").isEqualTo("LEI")
      .jsonPath("$.assessmentComment").isEqualTo("Legacy high comment")
      .jsonPath("$.provisionalAssessmentComment").isEmpty
      .jsonPath("$.riskTo").isEmpty
      .jsonPath("$.finalDate").isEqualTo("2023-07-20")
      .jsonPath("$.nextReviewDate").isEqualTo("2024-01-14")
  }

  @Test
  fun `returns a complete two-stage standard rating with both comments and dates`() {
    val standard = review(
      prisonerNumber = "S2222SS",
      assessmentDate = LocalDate.parse("2026-06-30"),
      interimResult = CsraResult.STANDARD,
      interimResultDate = LocalDate.parse("2026-06-30"),
      finalResult = CsraResult.STANDARD,
      finalResultDate = LocalDate.parse("2026-07-01"),
    )
    stage(standard, CsraAssessmentStage.PROVISIONAL, LocalDateTime.parse("2026-06-30T10:00:00"), comment = "pnc not checked on day 1. No evidence of increased risk.")
    stage(standard, CsraAssessmentStage.FINAL, LocalDateTime.parse("2026-07-01T11:00:00"), comment = "PNC checked. No issues found.")

    webTestClient.get().uri("/csra-review/prisoner/S2222SS/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.prisonId").isEqualTo("LEI")
      .jsonPath("$.assessmentComment").isEqualTo("PNC checked. No issues found.")
      .jsonPath("$.provisionalAssessmentComment").isEqualTo("pnc not checked on day 1. No evidence of increased risk.")
      .jsonPath("$.provisionalDate").isEqualTo("2026-06-30")
      .jsonPath("$.finalDate").isEqualTo("2026-07-01")
  }

  @Test
  fun `returns a high-risk-specific rating with risk-to, vulnerabilities and next review date`() {
    val highSpecific = review(
      prisonerNumber = "H3333HH",
      assessmentDate = LocalDate.parse("2026-07-01"),
      finalResult = CsraResult.HIGH_SPECIFIC,
      finalResultDate = LocalDate.parse("2026-07-01"),
    )
    stage(highSpecific, CsraAssessmentStage.FINAL, LocalDateTime.parse("2026-07-01T11:00:00"), comment = "History of racist incidents.") {
      riskTo.add(CsraAssessmentStageRiskToEntity(stage = this, category = CsraRiskToCategory.DIFFERENT_ETHNICITY, details = "Racist towards other ethnicities."))
      vulnerabilities.add(CsraAssessmentStageVulnerabilityEntity(stage = this, category = CsraVulnerabilityCategory.NEURODIVERSITY, details = "Autistic."))
    }
    nextReview(highSpecific, "H3333HH", LocalDate.parse("2027-05-06"))

    webTestClient.get().uri("/csra-review/prisoner/H3333HH/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.riskTo.length()").isEqualTo(1)
      .jsonPath("$.riskTo[0].category").isEqualTo("DIFFERENT_ETHNICITY")
      .jsonPath("$.riskTo[0].details").isEqualTo("Racist towards other ethnicities.")
      .jsonPath("$.vulnerabilities[0].category").isEqualTo("NEURODIVERSITY")
      .jsonPath("$.nextReviewDate").isEqualTo("2027-05-06")
  }

  @Test
  fun `returns a provisional rating when only a Day 1 result has been given`() {
    val provisional = review(
      prisonerNumber = "P4444PP",
      assessmentDate = LocalDate.parse("2026-05-07"),
      interimResult = CsraResult.HIGH_GENERAL,
      interimResultDate = LocalDate.parse("2026-05-07"),
    )
    stage(provisional, CsraAssessmentStage.PROVISIONAL, LocalDateTime.parse("2026-05-07T10:00:00"), comment = "No PNC or access to warrant. Very late arrival.")

    webTestClient.get().uri("/csra-review/prisoner/P4444PP/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("PROVISIONAL")
      .jsonPath("$.rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.provisional").isEqualTo(true)
      .jsonPath("$.provisionalAssessmentComment").isEqualTo("No PNC or access to warrant. Very late arrival.")
      .jsonPath("$.assessmentComment").isEmpty
      .jsonPath("$.provisionalDate").isEqualTo("2026-05-07")
      .jsonPath("$.finalDate").isEmpty
  }

  @Test
  fun `uses only the prisoner's latest review`() {
    val prisoner = "M5555MM"
    review(prisoner, LocalDate.parse("2024-01-01"), finalResult = CsraResult.HIGH, finalResultDate = LocalDate.parse("2024-01-01"), prisonId = "LEI")
    val latest = review(prisoner, LocalDate.parse("2026-02-01"), finalResult = CsraResult.STANDARD, finalResultDate = LocalDate.parse("2026-02-01"), prisonId = "MDI")

    webTestClient.get().uri("/csra-review/prisoner/$prisoner/current-rating")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.reviewId").isEqualTo(latest.id.toString())
  }
}
