package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.wiremock.PrisonerSearchMockServer.RollMemberStub
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraAssessmentStageRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.time.LocalDate
import java.time.LocalDateTime

class CsraAssessmentsInProgressResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraAssessmentStageRepository: CsraAssessmentStageRepository

  private val readRole = listOf("ROLE_CSRA_REVIEW__R")

  @BeforeEach
  fun setUp() {
    csraReviewRepository.deleteAll()
    hmppsAuth.stubGrantToken()
    seed()
  }

  private fun review(
    prisonerNumber: String,
    startedAt: LocalDateTime,
    type: CsraType = CsraType.CSRA_INITIAL_REVIEW,
    interimResult: CsraResult? = null,
    finalResult: CsraResult? = null,
    prisonId: String = "LEI",
    createdBy: String = "JBLOGGS",
    // Deliberately a month after the start date: the screen reports when the assessment was *started*, so a
    // response that read assessmentDate instead would fail rather than coincidentally pass.
    assessmentDate: LocalDate = startedAt.toLocalDate().plusMonths(1),
  ) = csraReviewRepository.saveAndFlush(
    CsraReviewEntity(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      assessmentDate = assessmentDate,
      type = type,
      interimResult = interimResult,
      interimResultDate = interimResult?.let { assessmentDate },
      finalResult = finalResult,
      finalResultDate = finalResult?.let { assessmentDate },
      createdAt = startedAt,
      createdBy = createdBy,
    ),
  )

  private fun seed() {
    // Started, no rating
    review("AS01", LocalDateTime.parse("2026-07-06T11:20:00"), createdBy = "JBLOGGS")
    review("AS02", LocalDateTime.parse("2026-07-05T08:45:00"), createdBy = "SCARTER")
    // Provisional rating entered, with a provisional stage carrying "assessed by/on"
    val provisional = review("PR01", LocalDateTime.parse("2026-07-06T09:00:00"), interimResult = CsraResult.HIGH_SPECIFIC, createdBy = "JBLOGGS")
    csraAssessmentStageRepository.saveAndFlush(
      CsraAssessmentStageEntity(
        csraReview = provisional,
        stage = CsraAssessmentStage.PROVISIONAL,
        completedBy = "MSTANLEY",
        completedAt = LocalDateTime.parse("2026-07-06T10:00:00"),
        prisonId = "LEI",
      ),
    )
    // Decoys
    review("CMP", LocalDateTime.parse("2026-07-01T09:00:00"), finalResult = CsraResult.STANDARD) // completed
    review("REV", LocalDateTime.parse("2026-07-02T09:00:00"), type = CsraType.CSRA_REVIEW) // a review, not an assessment
    review("OTHER", LocalDateTime.parse("2026-07-06T09:00:00"), prisonId = "BXI") // in progress at another prison
    review("LEG", LocalDateTime.parse("2026-07-02T09:00:00"), type = CsraType.RATING) // legacy null-result
    review("ASOUT", LocalDateTime.parse("2026-07-04T09:00:00")) // started here, since released
    review("ASGONE", LocalDateTime.parse("2026-07-04T09:00:00")) // started here, unknown to prisoner-search

    prisonerSearch.stubGetPrisonerNames(
      listOf(
        RollMemberStub("AS01", "Simon", "Kettleby", prisonId = "LEI"),
        RollMemberStub("AS02", "Gareth", "Winrow", prisonId = "LEI"),
        RollMemberStub("PR01", "Daniel", "Havers", prisonId = "LEI"),
        // Released: prisoner-search reports the released as OUT.
        RollMemberStub("ASOUT", "Mubashir", "Khan", prisonId = "OUT"),
        // ASGONE is absent from the response entirely.
      ),
    )
  }

  private fun get() = webTestClient.get().uri("/csra-review/prison/LEI/assessments-in-progress")
    .headers(setAuthorisation(roles = readRole))
    .exchange()

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri("/csra-review/prison/LEI/assessments-in-progress")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 with the wrong role`() {
    webTestClient.get().uri("/csra-review/prison/LEI/assessments-in-progress")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `splits in-progress assessments into started and provisional, excluding everything else`() {
    get()
      .expectStatus().isOk
      .expectBody()
      // Started: AS02 (07-05) then AS01 (07-06), ordered by started date
      .jsonPath("$.assessmentStarted.length()").isEqualTo(2)
      .jsonPath("$.assessmentStarted[0].prisonerNumber").isEqualTo("AS02")
      .jsonPath("$.assessmentStarted[0].firstName").isEqualTo("Gareth")
      .jsonPath("$.assessmentStarted[0].lastName").isEqualTo("Winrow")
      .jsonPath("$.assessmentStarted[0].startedOn").isEqualTo("2026-07-05")
      .jsonPath("$.assessmentStarted[0].startedBy").isEqualTo("SCARTER")
      .jsonPath("$.assessmentStarted[1].prisonerNumber").isEqualTo("AS01")
      // Provisional: PR01 with the stage's assessed-by/on and the interim rating
      .jsonPath("$.provisionalRatingEntered.length()").isEqualTo(1)
      .jsonPath("$.provisionalRatingEntered[0].prisonerNumber").isEqualTo("PR01")
      .jsonPath("$.provisionalRatingEntered[0].firstName").isEqualTo("Daniel")
      .jsonPath("$.provisionalRatingEntered[0].assessedOn").isEqualTo("2026-07-06")
      .jsonPath("$.provisionalRatingEntered[0].assessedBy").isEqualTo("MSTANLEY")
      .jsonPath("$.provisionalRatingEntered[0].rating").isEqualTo("HIGH_SPECIFIC")
      .jsonPath("$.provisionalRatingEntered[0].reviewId").isNotEmpty
  }

  @Test
  fun `excludes prisoners who have left the establishment`() {
    // The tidy-up that closes in-progress work only runs on the next admission, so a released prisoner's
    // assessment is still IN_PROGRESS against this prison. It must not reach the worklist.
    get()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.assessmentStarted[?(@.prisonerNumber == 'ASOUT')]").doesNotExist()
      .jsonPath("$.assessmentStarted[?(@.prisonerNumber == 'ASGONE')]").doesNotExist()
  }

  @Test
  fun `returns empty sections for an establishment with none in progress`() {
    webTestClient.get().uri("/csra-review/prison/MDI/assessments-in-progress")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.assessmentStarted.length()").isEqualTo(0)
      .jsonPath("$.provisionalRatingEntered.length()").isEqualTo(0)
  }
}
