package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraEvaluationResultCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraNextReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewNomisEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraCurrentRatingRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CsraRejectedReviewRepairService
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.InformationSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.RepairResult
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A NOMIS review rejected at approval does not set the prisoner's current rating or next review date (MAPA-526),
 * and prisoners rated by one before that change are put right by [CsraRejectedReviewRepairService].
 */
class CsraRejectedNomisReviewTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewRepository: CsraReviewRepository

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraCurrentRatingRepository: CsraCurrentRatingRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  @Autowired
  private lateinit var repairService: CsraRejectedReviewRepairService

  @BeforeEach
  fun clean() {
    csraNextReviewRepository.deleteAll()
    csraCurrentRatingRepository.deleteAll()
    csraReviewNomisRepository.deleteAll()
    csraReviewRepository.deleteAll()
  }

  /** A migrated NOMIS review. Saved in call order, so a later call is the more recently recorded review. */
  private fun nomisReview(
    assessmentDate: String,
    result: CsraResult,
    evaluationResultCode: CsraEvaluationResultCode?,
    nextReviewDate: String,
  ): CsraReviewEntity {
    val review = csraReviewRepository.saveAndFlush(
      CsraReviewEntity(
        prisonerNumber = PRISONER,
        prisonId = "LEI",
        assessmentDate = LocalDate.parse(assessmentDate),
        type = CsraType.NOMIS_REVIEW,
        finalResult = result,
        finalResultDate = LocalDate.parse(assessmentDate),
        nextReviewDate = LocalDate.parse(nextReviewDate),
        status = CsraReviewStatus.COMPLETE,
        createdAt = LocalDateTime.parse("${assessmentDate}T09:00:00"),
        createdBy = "NQP56Y",
      ),
    )
    csraReviewNomisRepository.saveAndFlush(CsraReviewNomisEntity(csraReview = review, evaluationResultCode = evaluationResultCode))
    return review
  }

  private fun currentRating() = csraCurrentRatingRepository.findByPrisonerNumber(PRISONER)
  private fun nextReview() = csraNextReviewRepository.findByPrisonerNumber(PRISONER)

  @Nested
  inner class CurrentRating {
    @Test
    fun `a rejected review recorded after the approved one on the same day does not set the rating`() {
      val approved = nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.APP, "2027-01-25")
      nomisReview("2026-10-27", CsraResult.STANDARD, CsraEvaluationResultCode.REJ, "2026-10-28")

      refreshCurrentRating(PRISONER)

      assertThat(currentRating()!!.rating).isEqualTo(CsraResult.HIGH)
      assertThat(currentRating()!!.setByReviewId).isEqualTo(approved.id)
      assertThat(nextReview()!!.setByReviewId).isEqualTo(approved.id)
      assertThat(nextReview()!!.nextReviewDate).isEqualTo(LocalDate.parse("2027-01-25"))
    }

    @Test
    fun `a rejected review on a later day does not set the rating`() {
      val approved = nomisReview("2026-10-01", CsraResult.STANDARD, CsraEvaluationResultCode.APP, "2027-10-01")
      nomisReview("2026-10-20", CsraResult.HIGH, CsraEvaluationResultCode.REJ, "2027-01-20")

      refreshCurrentRating(PRISONER)

      assertThat(currentRating()!!.rating).isEqualTo(CsraResult.STANDARD)
      assertThat(currentRating()!!.setByReviewId).isEqualTo(approved.id)
    }

    @Test
    fun `a review with no approval result still sets the rating`() {
      nomisReview("2026-10-01", CsraResult.STANDARD, CsraEvaluationResultCode.APP, "2027-10-01")
      val unapproved = nomisReview("2026-10-20", CsraResult.HIGH, null, "2027-01-20")

      refreshCurrentRating(PRISONER)

      assertThat(currentRating()!!.rating).isEqualTo(CsraResult.HIGH)
      assertThat(currentRating()!!.setByReviewId).isEqualTo(unapproved.id)
    }

    @Test
    fun `a prisoner whose only review was rejected has no rating`() {
      nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.REJ, "2027-01-25")

      refreshCurrentRating(PRISONER)

      assertThat(currentRating()?.rating).isNull()
      assertThat(nextReview()).isNull()
    }

    @Test
    fun `the rejected review is still in the prisoner's reviews`() {
      nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.APP, "2027-01-25")
      val rejected = nomisReview("2026-10-27", CsraResult.STANDARD, CsraEvaluationResultCode.REJ, "2026-10-28")

      refreshCurrentRating(PRISONER)

      assertThat(csraReviewRepository.findSummaryRows(PRISONER)).hasSize(2)
      assertThat(csraReviewRepository.findById(rejected.id!!)).isPresent
    }
  }

  @Nested
  inner class Repair {
    /** Puts the projections back to how they were before MAPA-526: set by the rejected review. */
    private fun rateFromRejectedAsBeforeTheFix(rejected: CsraReviewEntity) {
      refreshCurrentRating(PRISONER)
      csraCurrentRatingRepository.saveAndFlush(
        currentRating()!!.apply {
          rating = rejected.finalResult
          ratingDate = rejected.finalResultDate
          setByReviewId = rejected.id
        },
      )
      csraNextReviewRepository.deleteAll()
      csraNextReviewRepository.saveAndFlush(
        CsraNextReviewEntity(
          prisonerNumber = PRISONER,
          nextReviewDate = rejected.nextReviewDate,
          setByReviewId = rejected.id!!,
          updatedAt = LocalDateTime.parse("2026-11-16T12:28:24"),
        ),
      )
    }

    @Test
    fun `recalculates the rating, announces it, and finds nothing to do the second time`() {
      val approved = nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.APP, "2027-01-25")
      val rejected = nomisReview("2026-10-27", CsraResult.STANDARD, CsraEvaluationResultCode.REJ, "2026-10-28")
      rateFromRejectedAsBeforeTheFix(rejected)
      assertThat(currentRating()!!.rating).isEqualTo(CsraResult.STANDARD)

      assertThat(repairService.repair()).isEqualTo(RepairResult(found = 1, changed = 1, failed = 0))

      assertThat(currentRating()!!.rating).isEqualTo(CsraResult.HIGH)
      assertThat(currentRating()!!.setByReviewId).isEqualTo(approved.id)
      assertThat(nextReview()!!.setByReviewId).isEqualTo(approved.id)

      val event = getDomainEvents(1).single()
      assertThat(event.eventType).isEqualTo("cell.sharing.risk.assessment.amended")
      assertThat(event.additionalInformation!!.nomsNumber).isEqualTo(PRISONER)
      assertThat(event.additionalInformation!!.id).isNull()
      assertThat(event.additionalInformation!!.source).isEqualTo(InformationSource.NOMIS)

      assertThat(repairService.repair()).isEqualTo(RepairResult(found = 0, changed = 0, failed = 0))
    }

    @Test
    fun `a prisoner whose only review was rejected is left with no rating`() {
      val rejected = nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.REJ, "2027-01-25")
      rateFromRejectedAsBeforeTheFix(rejected)

      assertThat(repairService.repair()).isEqualTo(RepairResult(found = 1, changed = 1, failed = 0))

      assertThat(currentRating()?.rating).isNull()
      assertThat(nextReview()).isNull()
      assertThat(getDomainEvents(1)).hasSize(1)
    }

    @Test
    fun `prisoners not rated by a rejected review are not touched`() {
      nomisReview("2026-10-27", CsraResult.HIGH, CsraEvaluationResultCode.APP, "2027-01-25")
      nomisReview("2026-10-27", CsraResult.STANDARD, CsraEvaluationResultCode.REJ, "2026-10-28")
      refreshCurrentRating(PRISONER)

      assertThat(repairService.repair()).isEqualTo(RepairResult(found = 0, changed = 0, failed = 0))
      assertThat(getNumberOfMessagesCurrentlyOnQueue()).isZero()
    }
  }

  private companion object {
    const val PRISONER = "A1234RJ"
  }
}
