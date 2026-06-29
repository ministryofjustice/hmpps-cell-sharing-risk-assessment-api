package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraQuestionDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraResponseDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraReviewDetailDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.TestBase
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CsraReviewNomisRepositoryTest : TestBase() {

  @Autowired
  lateinit var repository: CsraReviewNomisRepository

  @Autowired
  lateinit var reviewRepository: CsraReviewRepository

  @PersistenceContext
  lateinit var entityManager: EntityManager

  @BeforeEach
  fun setup() {
    repository.deleteAll()
    reviewRepository.deleteAll()
  }

  private fun coreReview() = CsraReviewEntity(
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    assessmentDate = LocalDate.parse("2025-11-22"),
    type = CsraType.RATING,
    finalResult = CsraResult.HIGH,
    finalResultDate = LocalDate.parse("2025-11-22"),
    nextReviewDate = LocalDate.parse("2026-05-22"),
    createdAt = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
  )

  private val reviewDetails = listOf(
    CsraReviewDetailDto(
      code = "SECTION1",
      description = "First section",
      questions = listOf(
        CsraQuestionDto(
          code = "Q1",
          description = "Question one",
          responses = listOf(
            CsraResponseDto(code = "R1", answer = "Yes", comment = "first"),
            CsraResponseDto(code = "R2", answer = "No", comment = null),
          ),
        ),
        CsraQuestionDto(code = "Q2", description = null, responses = emptyList()),
      ),
    ),
    CsraReviewDetailDto(code = "SECTION2", description = null, questions = emptyList()),
  )

  @Test
  fun `persists an additional record linked one-to-one with the core review`() {
    val core = reviewRepository.saveAndFlush(coreReview())
    val saved = repository.saveAndFlush(
      CsraReviewNomisEntity(
        csraReview = core,
        score = BigDecimal("1000"),
        status = CsraStatus.A,
        approvedLevel = CsraLevel.HI,
        evaluationResultCode = CsraEvaluationResultCode.APP,
        committeeCode = CsraCommitteeCode.REVIEW,
        comment = "a comment",
        reviewDetails = reviewDetails,
      ),
    )

    assertThat(saved.id).isNotNull()
    assertThat(saved.id!!.version()).isEqualTo(7)

    val found = repository.findByCsraReviewId(core.id!!)!!
    assertThat(found.id).isEqualTo(saved.id)
    assertThat(found.csraReview.id).isEqualTo(core.id)
  }

  @Test
  fun `round-trips scalar fields and the question and answer JSONB blob`() {
    val core = reviewRepository.saveAndFlush(coreReview())
    repository.save(
      CsraReviewNomisEntity(
        csraReview = core,
        score = BigDecimal("1000"),
        status = CsraStatus.A,
        calculatedLevel = CsraLevel.MED,
        reviewLevel = CsraLevel.STANDARD,
        approvedLevel = CsraLevel.HI,
        evaluationResultCode = CsraEvaluationResultCode.APP,
        committeeCode = CsraCommitteeCode.REVIEW,
        reviewCommitteeCode = CsraCommitteeCode.GOV,
        comment = "assessment comment",
        reviewComment = "review comment",
        placementPrisonId = "MDI",
        reviewDetails = reviewDetails,
      ),
    )
    // force a real read back from the database, not the persistence-context cache
    entityManager.flush()
    entityManager.clear()

    val found = repository.findByCsraReviewId(core.id!!)!!
    assertThat(found.score).isEqualByComparingTo(BigDecimal("1000"))
    assertThat(found.status).isEqualTo(CsraStatus.A)
    assertThat(found.calculatedLevel).isEqualTo(CsraLevel.MED)
    assertThat(found.approvedLevel).isEqualTo(CsraLevel.HI)
    assertThat(found.evaluationResultCode).isEqualTo(CsraEvaluationResultCode.APP)
    assertThat(found.committeeCode).isEqualTo(CsraCommitteeCode.REVIEW)
    assertThat(found.placementPrisonId).isEqualTo("MDI")
    assertThat(found.reviewDetails).isEqualTo(reviewDetails)
  }
}
