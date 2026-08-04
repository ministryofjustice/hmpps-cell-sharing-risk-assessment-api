package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraCurrentRatingEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRatingSetReason
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

class CsraCurrentRatingResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

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
    status: CsraReviewStatus = CsraReviewStatus.IN_PROGRESS,
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      status = status,
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

  private fun get(prisonerNumber: String) = webTestClient.get().uri("/csra-review/prisoner/$prisonerNumber/current-rating")
    .headers(setAuthorisation(roles = readRole))
    .exchange()
    .expectStatus().isOk
    .expectBody()

  @Test
  fun `reports an unrated review in progress alongside the rating an earlier review produced`() {
    val prisoner = "IP001IP"
    // A completed Standard rating...
    val completed = review(
      prisonerNumber = prisoner,
      assessmentDate = LocalDate.parse("2026-01-10"),
      finalResult = CsraResult.STANDARD,
      finalResultDate = LocalDate.parse("2026-01-10"),
      prisonId = "LEI",
      status = CsraReviewStatus.COMPLETE,
    )
    // ...and a later, still unrated review. Starting one deliberately leaves the rating alone, so the
    // projection still cites the completed review.
    val started = review(
      prisonerNumber = prisoner,
      assessmentDate = LocalDate.parse("2026-02-01"),
      type = CsraType.CSRA_REVIEW,
      prisonId = "BXI",
    )

    get(prisoner)
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.rating").isEqualTo("STANDARD")
      .jsonPath("$.reviewId").isEqualTo(completed.id.toString())
      .jsonPath("$.type").isEqualTo("CSRA_INITIAL_REVIEW")
      // The in-progress record is a different review, and the UI needs its id to offer Continue/Cancel
      .jsonPath("$.inProgress.reviewId").isEqualTo(started.id.toString())
      .jsonPath("$.inProgress.type").isEqualTo("CSRA_REVIEW")
      .jsonPath("$.inProgress.prisonId").isEqualTo("BXI")
      .jsonPath("$.inProgress.startedBy").isEqualTo("NQP56Y")
  }

  @Test
  fun `reports a review in progress that is itself the record showing the current rating`() {
    val prisoner = "IP002IP"
    // An interim rating stands while the review is still open — the same record is both.
    val interim = review(
      prisonerNumber = prisoner,
      assessmentDate = LocalDate.parse("2026-02-01"),
      type = CsraType.CSRA_REVIEW,
      interimResult = CsraResult.HIGH_GENERAL,
      interimResultDate = LocalDate.parse("2026-02-01"),
      prisonId = "LEI",
    )

    get(prisoner)
      .jsonPath("$.rating").isEqualTo("HIGH_GENERAL")
      .jsonPath("$.provisional").isEqualTo(true)
      .jsonPath("$.reviewId").isEqualTo(interim.id.toString())
      // Equal ids are what tell the UI to render "an interim rating has been entered, complete the review"
      .jsonPath("$.inProgress.reviewId").isEqualTo(interim.id.toString())
      .jsonPath("$.inProgress.type").isEqualTo("CSRA_REVIEW")
  }

  @Test
  fun `omits the in-progress block when nothing is in progress`() {
    val prisoner = "IP003IP"
    review(
      prisonerNumber = prisoner,
      assessmentDate = LocalDate.parse("2026-01-10"),
      finalResult = CsraResult.STANDARD,
      finalResultDate = LocalDate.parse("2026-01-10"),
      status = CsraReviewStatus.COMPLETE,
    )

    get(prisoner)
      .jsonPath("$.status").isEqualTo("COMPLETE")
      .jsonPath("$.inProgress").doesNotExist()
  }

  @Test
  fun `picks the most recent review when more than one is in progress`() {
    val prisoner = "IP004IP"
    review(prisonerNumber = prisoner, assessmentDate = LocalDate.parse("2026-01-05"), prisonId = "LEI")
    val newest = review(prisonerNumber = prisoner, assessmentDate = LocalDate.parse("2026-03-09"), prisonId = "BXI")

    // Without an explicit ordering this depended on database row order, so the response was not
    // reproducible for a prisoner holding more than one in-progress review.
    repeat(3) {
      get(prisoner)
        .jsonPath("$.reviewId").isEqualTo(newest.id.toString())
        .jsonPath("$.inProgress.reviewId").isEqualTo(newest.id.toString())
    }
  }

  @Test
  fun `reports a next review date even for a prisoner with no rating`() {
    val prisoner = "NR005NR"
    // A next review date can outlive the rating that set it. An archived review is hidden from the service
    // entirely and is not in progress, so the prisoner reads as No rating while this row survives — the
    // review itself must stay, since csra_next_review cascades on delete.
    val archived = review(
      prisonerNumber = prisoner,
      assessmentDate = LocalDate.parse("2025-06-01"),
      status = CsraReviewStatus.ARCHIVED,
    )
    nextReview(archived, prisoner, LocalDate.parse("2026-06-01"))

    get(prisoner)
      .jsonPath("$.status").isEqualTo("NO_RATING")
      .jsonPath("$.rating").isEmpty
      .jsonPath("$.nextReviewDate").isEqualTo("2026-06-01")
  }

  @Test
  fun `does not fail when a rating row points at no review`() {
    val prisoner = "NP006NP"
    // set_by_review_id is nullable, so a rating row can exist without a review behind it. The code used to
    // assert it non-null and would have thrown rather than degrading to No rating.
    csraCurrentRatingRepository.saveAndFlush(
      CsraCurrentRatingEntity(
        prisonerNumber = prisoner,
        rating = CsraResult.STANDARD,
        ratingDate = LocalDate.parse("2026-01-10"),
        setByReviewId = null,
        setReason = CsraRatingSetReason.RATING_SAVED,
        setAt = LocalDateTime.parse("2026-01-10T09:00:00"),
      ),
    )

    get(prisoner).jsonPath("$.status").isEqualTo("NO_RATING")
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
