package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraClosureReason
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
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

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    csraAssessmentStageRepository.deleteAllInBatch()
    csraCurrentRatingRepository.deleteAllInBatch()
    csraNextReviewRepository.deleteAllInBatch()
    csraReviewNomisRepository.deleteAllInBatch()
    csraReviewRepository.deleteAllInBatch()
  }

  private fun review(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    finalResult: CsraResult,
    prisonId: String,
    status: CsraReviewStatus = CsraReviewStatus.COMPLETE,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = CsraType.NOMIS_REVIEW,
      finalResult = finalResult,
      finalResultDate = assessmentDate,
      status = status,
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

  private fun withStage(
    review: CsraReviewEntity,
    stage: CsraAssessmentStage,
    comment: String,
    completedAt: LocalDateTime,
    prisonId: String? = null,
    completedBy: String? = null,
  ) = csraAssessmentStageRepository.saveAndFlush(
    CsraAssessmentStageEntity(
      csraReview = review,
      stage = stage,
      assessmentComment = comment,
      completedAt = completedAt,
      prisonId = prisonId,
      completedBy = completedBy,
    ),
  )

  private fun withFinalStageRiskDetails(
    review: CsraReviewEntity,
    comment: String,
    riskTo: String,
    vulnerability: String,
  ) {
    // Built in full before saving: a review may only carry one FINAL stage, and the collections on a
    // freshly loaded stage cannot be added to outside a session.
    val stage = CsraAssessmentStageEntity(
      csraReview = review,
      stage = CsraAssessmentStage.FINAL,
      assessmentComment = comment,
    )
    stage.riskTo.add(
      CsraAssessmentStageRiskToEntity(
        stage = stage,
        category = CsraRiskToCategory.GANG_MEMBERS,
        details = riskTo,
      ),
    )
    stage.vulnerabilities.add(
      CsraAssessmentStageVulnerabilityEntity(
        stage = stage,
        category = CsraVulnerabilityCategory.MENTAL_HEALTH,
        details = vulnerability,
      ),
    )
    csraAssessmentStageRepository.saveAndFlush(stage)
  }

  private fun legacyPendingReview(prisonerNumber: String, assessmentDate: LocalDate, prisonId: String = "LEI") = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = CsraType.NOMIS_REVIEW,
      status = CsraReviewStatus.COMPLETE,
      createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
      createdBy = "NQP56Y",
    ),
  )

  private fun ratedReview(
    prisonerNumber: String,
    assessmentDate: LocalDate,
    type: CsraType,
    interimResult: CsraResult,
    prisonId: String,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      interimResult = interimResult,
      interimResultDate = assessmentDate,
      status = CsraReviewStatus.IN_PROGRESS,
      createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
      createdBy = "NQP56Y",
    ),
  )

  @Test
  fun `includes every distinct rating variant in the summary in UI order, excluding unrated legacy PEND reviews`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)", "MDI" to "Moorland (HMP)"))

    review("V4444VV", LocalDate.parse("2023-01-01"), CsraResult.HIGH, "LEI")
    review("V4444VV", LocalDate.parse("2023-02-01"), CsraResult.HIGH_GENERAL, "LEI")
    ratedReview(
      "V4444VV",
      LocalDate.parse("2023-03-01"),
      CsraType.CSRA_REVIEW,
      CsraResult.HIGH_GENERAL,
      "LEI",
    )
    ratedReview(
      "V4444VV",
      LocalDate.parse("2023-04-01"),
      CsraType.CSRA_INITIAL_ASSESSMENT,
      CsraResult.HIGH_GENERAL,
      "LEI",
    )
    review("V4444VV", LocalDate.parse("2023-05-01"), CsraResult.HIGH_SPECIFIC, "LEI")
    ratedReview(
      "V4444VV",
      LocalDate.parse("2023-06-01"),
      CsraType.CSRA_INITIAL_ASSESSMENT,
      CsraResult.HIGH_SPECIFIC,
      "MDI",
    )
    review("V4444VV", LocalDate.parse("2023-07-01"), CsraResult.STANDARD, "LEI")
    val standardLegacy = review("V4444VV", LocalDate.parse("2023-08-01"), CsraResult.STANDARD, "LEI")
    withNomis(standardLegacy, calculatedLevel = CsraLevel.STANDARD)
    val low = review("V4444VV", LocalDate.parse("2023-09-01"), CsraResult.STANDARD, "LEI")
    withNomis(low, calculatedLevel = CsraLevel.LOW)
    val med = review("V4444VV", LocalDate.parse("2023-10-01"), CsraResult.STANDARD, "LEI")
    withNomis(med, calculatedLevel = CsraLevel.MED)
    val pending = legacyPendingReview("V4444VV", LocalDate.parse("2023-11-01"), "LEI")
    withNomis(pending, calculatedLevel = CsraLevel.PEND)

    webTestClient.get().uri("/csra-review/prisoner/V4444VV/history?ratings=HIGH")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.summary.ratings.length()").isEqualTo(10)
      .jsonPath("$.summary.ratings[0]").isEqualTo("HIGH")
      .jsonPath("$.summary.ratings[1]").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.summary.ratings[2]").isEqualTo("HIGH_GENERAL_INTERIM")
      .jsonPath("$.summary.ratings[3]").isEqualTo("HIGH_GENERAL_PROVISIONAL")
      .jsonPath("$.summary.ratings[4]").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.summary.ratings[5]").isEqualTo("HIGH_SPECIFIC_PROVISIONAL")
      .jsonPath("$.summary.ratings[6]").isEqualTo("STANDARD")
      .jsonPath("$.summary.ratings[7]").isEqualTo("STANDARD_LEGACY")
      .jsonPath("$.summary.ratings[8]").isEqualTo("LOW")
      .jsonPath("$.summary.ratings[9]").isEqualTo("MED")
      .jsonPath("$.summary.ratings").value<List<String>> { assertThat(it).doesNotContain("PEND") }
      .jsonPath("$.totalElements").isEqualTo(1)
  }

  @Test
  fun `filters history by the exact legacy LOW variant, not by the broader standard bucket`() {
    val standard = review("Q1111QQ", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    withNomis(standard, calculatedLevel = CsraLevel.STANDARD)
    val low = review("Q1111QQ", LocalDate.parse("2024-02-01"), CsraResult.STANDARD, "LEI")
    withNomis(low, calculatedLevel = CsraLevel.LOW)
    val med = review("Q1111QQ", LocalDate.parse("2024-03-01"), CsraResult.STANDARD, "LEI")
    withNomis(med, calculatedLevel = CsraLevel.MED)

    webTestClient.get().uri("/csra-review/prisoner/Q1111QQ/history?ratings=LOW")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].legacy.level").isEqualTo("LOW")
      .jsonPath("$.content[0].rating").isEqualTo("STANDARD")
  }

  @Test
  fun `a legacy review with reviewer PEND over calculated STANDARD is offered and found under Standard (legacy)`() {
    val review = review("R1111RR", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    withNomis(review, calculatedLevel = CsraLevel.STANDARD, reviewLevel = CsraLevel.PEND)

    webTestClient.get().uri("/csra-review/prisoner/R1111RR/history?ratings=STANDARD_LEGACY")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.summary.ratings.length()").isEqualTo(1)
      .jsonPath("$.summary.ratings[0]").isEqualTo("STANDARD_LEGACY")
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].legacy.level").isEqualTo("STANDARD")
  }

  @Test
  fun `a legacy review with reviewer LOW under calculated STANDARD is found under Standard (legacy), not Low`() {
    val review = review("R2222RR", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    withNomis(review, calculatedLevel = CsraLevel.STANDARD, reviewLevel = CsraLevel.LOW)

    webTestClient.get().uri("/csra-review/prisoner/R2222RR/history?ratings=STANDARD_LEGACY")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.summary.ratings.length()").isEqualTo(1)
      .jsonPath("$.summary.ratings[0]").isEqualTo("STANDARD_LEGACY")
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].legacy.level").isEqualTo("STANDARD")

    webTestClient.get().uri("/csra-review/prisoner/R2222RR/history?ratings=LOW")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
  }

  @Test
  fun `a legacy review with reviewer STANDARD under calculated HI is offered only High, and is found under it`() {
    val review = review("R3333RR", LocalDate.parse("2024-01-01"), CsraResult.HIGH, "LEI")
    withNomis(review, calculatedLevel = CsraLevel.HI, reviewLevel = CsraLevel.STANDARD)

    webTestClient.get().uri("/csra-review/prisoner/R3333RR/history?ratings=HIGH")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.summary.ratings.length()").isEqualTo(1)
      .jsonPath("$.summary.ratings[0]").isEqualTo("HIGH")
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].legacy.level").isEqualTo("HI")
  }

  @Test
  fun `filters history by the exact high-general variant, not by the broader HIGH family`() {
    review("Q2222QQ", LocalDate.parse("2024-01-01"), CsraResult.HIGH, "LEI")
    review("Q2222QQ", LocalDate.parse("2024-02-01"), CsraResult.HIGH_GENERAL, "LEI")
    ratedReview(
      "Q2222QQ",
      LocalDate.parse("2024-03-01"),
      CsraType.CSRA_INITIAL_ASSESSMENT,
      CsraResult.HIGH_GENERAL,
      "LEI",
    )
    review("Q2222QQ", LocalDate.parse("2024-04-01"), CsraResult.HIGH_SPECIFIC, "LEI")

    webTestClient.get().uri("/csra-review/prisoner/Q2222QQ/history?ratings=HIGH_GENERAL")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].recordedDate").isEqualTo("2024-02-01")
  }

  @Test
  fun `returns 400 when a ratings value is not valid`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/history?ratings=BAD_VALUE")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("InvalidRatingFilter")
      .jsonPath("$.userMessage").value<String> { it.contains("Invalid CSRA rating filter 'BAD_VALUE'") }
      .jsonPath("$.userMessage").value<String> { it.contains("HIGH") }
      .jsonPath("$.userMessage").value<String> { it.contains("HIGH_GENERAL") }
      .jsonPath("$.userMessage").value<String> { it.contains("STANDARD") }
      .jsonPath("$.userMessage").value<String> { assertThat(it).doesNotContain("PEND") }
  }

  @Test
  fun `returns 400 when filtering by PEND, which is no longer a rating filter`() {
    webTestClient.get().uri("/csra-review/prisoner/A1234BC/history?ratings=PEND")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("InvalidRatingFilter")
      .jsonPath("$.userMessage").value<String> { assertThat(it).contains("Invalid CSRA rating filter 'PEND'") }
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
    withFinalStageRiskDetails(highSpecific, "History of racist incidents.", "Gang members", "Mental health")

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
      .jsonPath("$.summary.ratings.length()").isEqualTo(3)
      .jsonPath("$.summary.ratings[0]").isEqualTo("HIGH")
      .jsonPath("$.summary.ratings[1]").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.summary.ratings[2]").isEqualTo("STANDARD")
      .jsonPath("$.summary.establishments.length()").isEqualTo(2)
      .jsonPath("$.summary.establishments[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.summary.establishments[0].prisonName").isEqualTo("Leeds (HMP)")
      .jsonPath("$.summary.establishments[1].prisonId").isEqualTo("MDI")
      .jsonPath("$.summary.establishments[1].prisonName").isEqualTo("Moorland (HMP)")
      .jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.content.length()").isEqualTo(3)
      .jsonPath("$.content[0].rating").isEqualTo("HIGH_SPECIFIC")
      // Seeded as the legacy NOMIS REVIEW type, which buckets to REVIEW — the point of the field is
      // that a consumer never has to know which of the eight type values it was (MAPA-366).
      .jsonPath("$.content[0].assessmentType").isEqualTo("REVIEW")
      .jsonPath("$.content[0].reviewComment").isEqualTo("History of racist incidents.")
      .jsonPath("$.content[0].finalRating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.content[0].finalReviewComment").isEqualTo("History of racist incidents.")
      .jsonPath("$.content[0].finalRecordedDate").isEqualTo("2025-10-11")
      .jsonPath("$.content[0].provisionalRating").doesNotExist()
      .jsonPath("$.content[0].prisonId").isEqualTo("MDI")
      .jsonPath("$.content[0].recordedDate").isEqualTo("2025-10-11")
      .jsonPath("$.content[0].riskTo.length()").isEqualTo(1)
      .jsonPath("$.content[0].riskTo[0].category").isEqualTo("GANG_MEMBERS")
      .jsonPath("$.content[0].riskTo[0].details").isEqualTo("Gang members")
      .jsonPath("$.content[0].vulnerabilities.length()").isEqualTo(1)
      .jsonPath("$.content[0].vulnerabilities[0].category").isEqualTo("MENTAL_HEALTH")
      .jsonPath("$.content[0].vulnerabilities[0].details").isEqualTo("Mental health")
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
  fun `an approved legacy review reports both its dates separately`() {
    val approved = review("C1111CC", LocalDate.parse("2012-05-23"), CsraResult.HIGH, "LEI")
    withNomis(
      approved,
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
      .jsonPath("$.content[0].legacy.approvalStatus").isEqualTo("APPROVED")
      .jsonPath("$.content[0].legacy.level").isEqualTo("HI")
      // The design shows the assessment and approval dates on separate lines, so they must not collapse.
      .jsonPath("$.content[0].legacy.assessmentDate").isEqualTo("2012-05-23")
      .jsonPath("$.content[0].legacy.approvalDate").isEqualTo("2012-06-01")
  }

  @Test
  fun `a rejected legacy review reports not approved`() {
    val rejected = review("R1111RR", LocalDate.parse("2011-10-24"), CsraResult.STANDARD, "LEI")
    withNomis(
      rejected,
      calculatedLevel = CsraLevel.STANDARD,
      evaluationResultCode = CsraEvaluationResultCode.REJ,
    )

    webTestClient.get().uri("/csra-review/prisoner/R1111RR/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].legacy.approvalStatus").isEqualTo("NOT_APPROVED")
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
  fun `legacy LOW and MED rows do not filter as standard`() {
    val low = review("B1111BB", LocalDate.parse("2010-03-13"), CsraResult.STANDARD, "LEI")
    withNomis(low, calculatedLevel = CsraLevel.LOW)
    val med = review("B1111BB", LocalDate.parse("2009-09-29"), CsraResult.STANDARD, "LEI")
    withNomis(med, calculatedLevel = CsraLevel.MED)

    webTestClient.get().uri("/csra-review/prisoner/B1111BB/history?ratings=STANDARD")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)

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
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].rating").isEqualTo("HIGH")
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

  @Test
  fun `an archived review is absent from the rows, the counts and the establishment list`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    review("A9999AA", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI")
    review("A9999AA", LocalDate.parse("2025-01-01"), CsraResult.HIGH_GENERAL, "MDI", CsraReviewStatus.ARCHIVED)

    webTestClient.get().uri("/csra-review/prisoner/A9999AA/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].recordedDate").isEqualTo("2024-01-01")
      // The counts sit beside the rows, so they have to drop it too - they come from a separate query.
      .jsonPath("$.summary.totalCsras").isEqualTo(1)
      .jsonPath("$.summary.highCount").isEqualTo(0)
      .jsonPath("$.summary.standardCount").isEqualTo(1)
      .jsonPath("$.summary.lastAssessmentDate").isEqualTo("2024-01-01")
      // MDI was the archived review's prison and is its only appearance.
      .jsonPath("$.summary.establishments.length()").isEqualTo(1)
      .jsonPath("$.summary.establishments[0].prisonId").isEqualTo("LEI")
  }

  @Test
  fun `a closed review is still shown - its rating stands`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    review("A9998AA", LocalDate.parse("2024-01-01"), CsraResult.STANDARD, "LEI", CsraReviewStatus.CLOSED)

    webTestClient.get().uri("/csra-review/prisoner/A9998AA/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.summary.totalCsras").isEqualTo(1)
      .jsonPath("$.content[0].closureReason").isEmpty
  }

  @Test
  fun `a review closed before it reached a final rating reports why it was closed`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = "A9997AA",
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2024-03-01"),
        type = CsraType.CSRA_REVIEW,
        interimResult = CsraResult.HIGH_GENERAL,
        interimResultDate = LocalDate.parse("2024-03-02"),
        status = CsraReviewStatus.CLOSED,
        closureReason = CsraClosureReason.NOT_COMPLETED_PRISONER_TRANSFER,
        createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
        createdBy = "NQP56Y",
      ),
    )

    webTestClient.get().uri("/csra-review/prisoner/A9997AA/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].closureReason").isEqualTo("NOT_COMPLETED_PRISONER_TRANSFER")
      .jsonPath("$.content[0].provisionalRating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].finalRating").doesNotExist()
  }

  @Test
  fun `a review that reached both stages reports each stage's own comment and date`() {
    prisonRegister.stubGetPrisons(
      mapOf(
        "LEI" to "Leeds (HMP)",
        "BXI" to "Brixton (HMP)",
        "MDI" to "Moorland (HMP)",
      ),
    )
    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = "T1111TT",
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2025-01-01"),
        type = CsraType.CSRA_REVIEW,
        interimResult = CsraResult.HIGH_GENERAL,
        interimResultDate = LocalDate.parse("2025-01-02"),
        finalResult = CsraResult.STANDARD,
        finalResultDate = LocalDate.parse("2025-01-10"),
        status = CsraReviewStatus.COMPLETE,
        createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
        createdBy = "NQP56Y",
      ),
    )
    withStage(
      review,
      CsraAssessmentStage.INTERIM,
      "Day 2 assessment complete.",
      LocalDateTime.parse("2025-01-03T09:00:00"),
      prisonId = "BXI",
      completedBy = "INTERIM_REVIEWER",
    )
    withStage(
      review,
      CsraAssessmentStage.FINAL,
      "PNC checked. No issues found.",
      LocalDateTime.parse("2025-01-12T09:00:00"),
      prisonId = "MDI",
    )

    webTestClient.get().uri("/csra-review/prisoner/T1111TT/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // Each rating carries the comment of the stage that produced it, never the other stage's.
      .jsonPath("$.content[0].provisionalRating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].provisionalReviewComment").isEqualTo("Day 2 assessment complete.")
      .jsonPath("$.content[0].finalRating").isEqualTo("STANDARD")
      .jsonPath("$.content[0].finalReviewComment").isEqualTo("PNC checked. No issues found.")
      // The stage's completion date wins over the review's result date, as on the current-rating endpoint.
      .jsonPath("$.content[0].provisionalRecordedDate").isEqualTo("2025-01-03")
      .jsonPath("$.content[0].finalRecordedDate").isEqualTo("2025-01-12")
      // Stage-specific prisons must not come from the review-level compatibility field.
      .jsonPath("$.content[0].provisionalPrisonId").isEqualTo("BXI")
      .jsonPath("$.content[0].provisionalPrisonName").isEqualTo("Brixton (HMP)")
      .jsonPath("$.content[0].interimReviewer").isEqualTo("INTERIM_REVIEWER")
      .jsonPath("$.content[0].finalPrisonId").isEqualTo("MDI")
      .jsonPath("$.content[0].finalPrisonName").isEqualTo("Moorland (HMP)")
      // The deprecated fields still describe the rating that stands, for consumers yet to migrate.
      .jsonPath("$.content[0].rating").isEqualTo("STANDARD")
      .jsonPath("$.content[0].reviewComment").isEqualTo("PNC checked. No issues found.")
      .jsonPath("$.content[0].recordedDate").isEqualTo("2025-01-10")
      .jsonPath("$.content[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.content[0].prisonName").isEqualTo("Leeds (HMP)")
  }

  @Test
  fun `a provisional initial assessment does not report an interim reviewer`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val assessment = ratedReview(
      "T5555TT",
      LocalDate.parse("2025-04-01"),
      CsraType.CSRA_INITIAL_ASSESSMENT,
      CsraResult.HIGH_GENERAL,
      "LEI",
    )
    withStage(
      assessment,
      CsraAssessmentStage.PROVISIONAL,
      "Day 1 assessment.",
      LocalDateTime.parse("2025-04-01T09:00:00"),
      prisonId = "LEI",
      completedBy = "PROVISIONAL_ASSESSOR",
    )

    webTestClient.get().uri("/csra-review/prisoner/T5555TT/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].provisionalRating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.content[0].interimReviewer").doesNotExist()
  }

  @Test
  fun `a completed review summary does not use provisional risk details when the final stage has none`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = "T4444TT",
        prisonId = "LEI",
        assessmentDate = LocalDate.parse("2025-03-01"),
        type = CsraType.CSRA_REVIEW,
        interimResult = CsraResult.HIGH_SPECIFIC,
        interimResultDate = LocalDate.parse("2025-03-01"),
        finalResult = CsraResult.HIGH_SPECIFIC,
        finalResultDate = LocalDate.parse("2025-03-10"),
        status = CsraReviewStatus.COMPLETE,
        createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
        createdBy = "NQP56Y",
      ),
    )
    val provisional = CsraAssessmentStageEntity(
      csraReview = review,
      stage = CsraAssessmentStage.INTERIM,
      assessmentComment = "Provisional high risk.",
      completedAt = LocalDateTime.parse("2025-03-01T09:00:00"),
    )
    provisional.riskTo.add(
      CsraAssessmentStageRiskToEntity(
        stage = provisional,
        category = CsraRiskToCategory.GANG_MEMBERS,
        details = "Gang members",
      ),
    )
    csraAssessmentStageRepository.saveAndFlush(provisional)
    csraAssessmentStageRepository.saveAndFlush(
      CsraAssessmentStageEntity(
        csraReview = review,
        stage = CsraAssessmentStage.FINAL,
        assessmentComment = "Final high risk.",
        completedAt = LocalDateTime.parse("2025-03-10T09:00:00"),
      ),
    )

    webTestClient.get().uri("/csra-review/prisoner/T4444TT/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].provisionalRating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.content[0].finalRating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.content[0].riskTo").isEmpty
  }

  @Test
  fun `a review with only a provisional rating reports no final values and the provisional stage's risk details`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val review = ratedReview("T2222TT", LocalDate.parse("2025-02-01"), CsraType.CSRA_REVIEW, CsraResult.HIGH_SPECIFIC, "LEI")
    val stage = withStage(review, CsraAssessmentStage.INTERIM, "Interim high risk.", LocalDateTime.parse("2025-02-04T09:00:00"))
    stage.riskTo.add(CsraAssessmentStageRiskToEntity(stage = stage, category = CsraRiskToCategory.GANG_MEMBERS, details = "Gang members"))
    stage.vulnerabilities.add(
      CsraAssessmentStageVulnerabilityEntity(stage = stage, category = CsraVulnerabilityCategory.MENTAL_HEALTH, details = "Mental health"),
    )
    csraAssessmentStageRepository.saveAndFlush(stage)

    webTestClient.get().uri("/csra-review/prisoner/T2222TT/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].provisionalRating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.content[0].provisionalReviewComment").isEqualTo("Interim high risk.")
      .jsonPath("$.content[0].provisionalRecordedDate").isEqualTo("2025-02-04")
      .jsonPath("$.content[0].finalRating").doesNotExist()
      .jsonPath("$.content[0].finalReviewComment").doesNotExist()
      .jsonPath("$.content[0].finalRecordedDate").doesNotExist()
      // With no final stage the risk details come from the provisional one, matching the rating shown.
      .jsonPath("$.content[0].riskTo[0].details").isEqualTo("Gang members")
      .jsonPath("$.content[0].vulnerabilities[0].details").isEqualTo("Mental health")
  }

  @Test
  fun `a legacy review's single NOMIS comment describes whichever rating the row carries`() {
    prisonRegister.stubGetPrisons(mapOf("LEI" to "Leeds (HMP)"))
    val legacyFinal = review("T3333TT", LocalDate.parse("2024-05-01"), CsraResult.STANDARD, "LEI")
    withNomisComment(legacyFinal, "Legacy final comment")
    val legacyProvisional = ratedReview("T3333TT", LocalDate.parse("2024-06-01"), CsraType.NOMIS_REVIEW, CsraResult.HIGH, "LEI")
    withNomisComment(legacyProvisional, "Legacy provisional comment")

    webTestClient.get().uri("/csra-review/prisoner/T3333TT/history")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].provisionalReviewComment").isEqualTo("Legacy provisional comment")
      .jsonPath("$.content[0].finalReviewComment").doesNotExist()
      .jsonPath("$.content[1].finalReviewComment").isEqualTo("Legacy final comment")
      .jsonPath("$.content[1].provisionalReviewComment").doesNotExist()
  }
}
