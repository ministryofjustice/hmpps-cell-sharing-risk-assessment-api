package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.core.converter.ModelConverters
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

class CsraAssessmentFinalStageRequestTest {
  private val objectMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

  @Test
  fun `final request preserves the flat payload and all shared answers`() {
    val json = """
      {
        "rating": "HIGH_SPECIFIC",
        "prisonId": "LEI",
        "assessmentComment": "Final assessment",
        "dpsChecked": true,
        "perChecked": false,
        "warrantChecked": true,
        "pncChecked": false,
        "offenceMurderManslaughter": true,
        "offenceAssistingSuicide": false,
        "offenceSexualAssault": true,
        "offenceRepeatedViolence": false,
        "offencePrejudiceMotivated": true,
        "offenceArson": false,
        "offenceKidnapHostage": true,
        "offenceEvidence": [
          {
            "offence": "MURDER_MANSLAUGHTER",
            "sources": ["OTHER"],
            "otherSourceDetail": "Police intelligence",
            "details": "Evidence of assault"
          }
        ],
        "officerSpokeToPrisoner": true,
        "likelyToHarmCellmate": true,
        "likelyToHarmCellmateDetail": "Threatened cellmate",
        "significantlyVulnerable": true,
        "significantlyVulnerableDetail": "Vulnerable to assault",
        "causeForConcernSharing": true,
        "causeForConcernSharingDetail": "Aggressive behaviour",
        "otherHighRiskIndicators": true,
        "otherHighRiskIndicatorsDetail": "Security intelligence",
        "seenByHealthcare": true,
        "healthcareIncreasedRisk": true,
        "healthcareIncreasedRiskDetail": "Healthcare concerns",
        "riskTo": [{"category": "DIFFERENT_ETHNICITY", "details": "Prejudice"}],
        "vulnerabilities": [{"category": "NEURODIVERSITY", "details": "Autistic"}],
        "nextReviewDate": "2027-06-01"
      }
    """.trimIndent()

    val request = objectMapper.readValue<CsraAssessmentFinalStageRequest>(json)

    assertThat(request.nextReviewDate).isEqualTo(LocalDate.parse("2027-06-01"))
    assertThat(objectMapper.valueToTree<JsonNode>(request)).isEqualTo(objectMapper.readTree(json))
  }

  @Test
  fun `final request defaults the review date and unanswered fields`() {
    val request = objectMapper.readValue<CsraAssessmentFinalStageRequest>(
      """{"rating":"STANDARD","prisonId":"LEI","assessmentComment":"Final assessment"}""",
    )

    assertThat(request.nextReviewDate).isNull()
    assertThat(request.pncChecked).isNull()
    assertThat(request.offenceEvidence).isEmpty()
    assertThat(request.riskTo).isEmpty()
    assertThat(request.vulnerabilities).isEmpty()
  }

  @Test
  fun `final request inherits shared field validation`() {
    Validation.buildDefaultValidatorFactory().use { factory ->
      val violations = factory.validator.validate(
        CsraAssessmentFinalStageRequest(rating = CsraResult.STANDARD, prisonId = "", assessmentComment = ""),
      )

      assertThat(violations.map { it.propertyPath.toString() })
        .containsExactlyInAnyOrder("prisonId", "assessmentComment")
    }
  }

  @Test
  fun `only the final request schema includes the review date`() {
    val schemas = ModelConverters.getInstance().read(CsraAssessmentFinalStageRequest::class.java)
    val finalSchema = schemas.getValue("CsraAssessmentFinalStageRequest")
    val stageSchema = ModelConverters.getInstance().read(CsraAssessmentStageRequest::class.java)
      .getValue("CsraAssessmentStageRequest")

    assertThat(stageSchema.properties).doesNotContainKey("nextReviewDate")
    assertThat(finalSchema.properties).containsKey("nextReviewDate")
    assertThat(finalSchema.properties.keys).containsAll(stageSchema.properties.keys)
    assertThat(finalSchema.required).contains("rating", "prisonId", "assessmentComment")
  }
}
