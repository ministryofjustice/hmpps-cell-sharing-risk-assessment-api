package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration.TestBase
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStage
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageRiskToEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageVulnerabilityEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import java.time.LocalDate
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CsraAssessmentStageRepositoryTest : TestBase() {

  @Autowired
  lateinit var repository: CsraAssessmentStageRepository

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
    assessmentDate = LocalDate.parse("2026-05-07"),
    type = CsraType.CSRA_INITIAL_REVIEW,
    interimResult = CsraResult.HIGH_GENERAL,
    interimResultDate = LocalDate.parse("2026-05-07"),
    finalResult = CsraResult.HIGH_SPECIFIC,
    finalResultDate = LocalDate.parse("2026-05-08"),
    createdAt = LocalDateTime.parse("2026-05-07T09:00:00"),
    createdBy = "NQP56Y",
  )

  @Test
  fun `round-trips both stages, answered and unanswered columns and child selections`() {
    val core = reviewRepository.saveAndFlush(coreReview())

    repository.save(
      CsraAssessmentStageEntity(
        csraReview = core,
        stage = CsraAssessmentStage.PROVISIONAL,
        completedBy = "NQP56Y",
        completedAt = LocalDateTime.parse("2026-05-07T09:30:00"),
        prisonId = "LEI",
        assessmentComment = "Provisional, PNC not yet available",
        questionSetVersion = 1,
        dpsChecked = true,
        perChecked = true,
        warrantChecked = false,
        pncChecked = null, // not answered on Day 1
        offenceMurderManslaughter = false,
        officerSpokeToPrisoner = true,
      ),
    )

    val finalStage = CsraAssessmentStageEntity(
      csraReview = core,
      stage = CsraAssessmentStage.FINAL,
      completedBy = "ABC12D",
      completedAt = LocalDateTime.parse("2026-05-08T11:00:00"),
      prisonId = "LEI",
      assessmentComment = "Final rating: can share with specific prisoners",
      questionSetVersion = 1,
      dpsChecked = true,
      perChecked = true,
      warrantChecked = true,
      pncChecked = true,
      offenceMurderManslaughter = false,
      offenceSexualAssault = true,
    )
    finalStage.riskTo.add(
      CsraAssessmentStageRiskToEntity(stage = finalStage, category = CsraRiskToCategory.SPECIFIC_PERSONS, details = "Co-defendant A9999ZZ"),
    )
    finalStage.riskTo.add(
      CsraAssessmentStageRiskToEntity(stage = finalStage, category = CsraRiskToCategory.GANG_MEMBERS),
    )
    finalStage.vulnerabilities.add(
      CsraAssessmentStageVulnerabilityEntity(stage = finalStage, category = CsraVulnerabilityCategory.MENTAL_HEALTH),
    )
    repository.save(finalStage)

    // force a real read back from the database, not the persistence-context cache
    entityManager.flush()
    entityManager.clear()

    val stages = repository.findAllByCsraReviewId(core.id!!).associateBy { it.stage }
    assertThat(stages.keys).containsExactlyInAnyOrder(CsraAssessmentStage.PROVISIONAL, CsraAssessmentStage.FINAL)

    val provisional = stages.getValue(CsraAssessmentStage.PROVISIONAL)
    assertThat(provisional.warrantChecked).isFalse()
    assertThat(provisional.pncChecked).isNull()
    assertThat(provisional.officerSpokeToPrisoner).isTrue()

    val found = repository.findByCsraReviewIdAndStage(core.id!!, CsraAssessmentStage.FINAL)!!
    assertThat(found.pncChecked).isTrue()
    assertThat(found.offenceSexualAssault).isTrue()
    assertThat(found.riskTo.map { it.category })
      .containsExactlyInAnyOrder(CsraRiskToCategory.SPECIFIC_PERSONS, CsraRiskToCategory.GANG_MEMBERS)
    assertThat(found.vulnerabilities.map { it.category }).containsExactly(CsraVulnerabilityCategory.MENTAL_HEALTH)
  }

  @Test
  fun `enforces one row per review and stage`() {
    val core = reviewRepository.saveAndFlush(coreReview())
    repository.saveAndFlush(CsraAssessmentStageEntity(csraReview = core, stage = CsraAssessmentStage.PROVISIONAL))

    assertThatThrownBy {
      repository.saveAndFlush(CsraAssessmentStageEntity(csraReview = core, stage = CsraAssessmentStage.PROVISIONAL))
    }.isNotNull()
  }

  @Test
  fun `cascades delete to child selections`() {
    val core = reviewRepository.saveAndFlush(coreReview())
    val stage = CsraAssessmentStageEntity(csraReview = core, stage = CsraAssessmentStage.FINAL)
    stage.riskTo.add(CsraAssessmentStageRiskToEntity(stage = stage, category = CsraRiskToCategory.OTHER, details = "Witness intimidation"))
    stage.vulnerabilities.add(CsraAssessmentStageVulnerabilityEntity(stage = stage, category = CsraVulnerabilityCategory.OLD_PEOPLE))
    val saved = repository.saveAndFlush(stage)

    repository.delete(saved)
    entityManager.flush()
    entityManager.clear()

    assertThat(repository.findAllByCsraReviewId(core.id!!)).isEmpty()
    assertThat(
      entityManager.createQuery("select count(r) from CsraAssessmentStageRiskToEntity r").singleResult,
    ).isEqualTo(0L)
    assertThat(
      entityManager.createQuery("select count(v) from CsraAssessmentStageVulnerabilityEntity v").singleResult,
    ).isEqualTo(0L)
  }
}
