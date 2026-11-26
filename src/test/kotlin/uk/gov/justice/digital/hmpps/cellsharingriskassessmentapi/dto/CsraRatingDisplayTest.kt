package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

class CsraRatingDisplayTest {

  // The MAPA-299 (header) and MAPA-303 (Overview card) designs, row by row. NONE stands for null.
  @ParameterizedTest(name = "{0} {1} -> \"{2}\" / \"{3}\"")
  @CsvSource(
    delimiter = '|',
    value = [
      "NONE          | NONE        | No rating                          | No rating",
      "STANDARD      | FINAL       | Standard risk                      | Standard",
      "HIGH          | FINAL       | High risk                          | High",
      "HIGH_GENERAL  | FINAL       | High risk – general                | High – general",
      "HIGH_SPECIFIC | FINAL       | High risk – specific               | High – specific",
      "HIGH_GENERAL  | PROVISIONAL | High risk – general (provisional)  | High – general",
      "HIGH_SPECIFIC | PROVISIONAL | High risk – specific (provisional) | High – specific",
      "HIGH_GENERAL  | INTERIM     | High risk – general (interim)      | High – general",
      "HIGH_SPECIFIC | INTERIM     | High risk – specific (interim)     | High – specific",
      "STANDARD      | PROVISIONAL | Standard risk (provisional)        | Standard",
      "STANDARD      | INTERIM     | Standard risk (interim)            | Standard",
    ],
  )
  fun `describes each rating as the profile designs show it`(rating: String, stage: String, full: String, short: String) {
    val result = rating.toResult()
    val ratingStage = stage.toStage()

    assertThat(describeRating(result, ratingStage)).isEqualTo(full)
    assertThat(describeRatingShort(result)).isEqualTo(short)
  }

  @ParameterizedTest(name = "{0} {1} shows a review due date: {2}")
  @CsvSource(
    delimiter = '|',
    value = [
      "HIGH          | FINAL       | true",
      "HIGH_GENERAL  | FINAL       | true",
      "HIGH_SPECIFIC | FINAL       | true",
      "HIGH_GENERAL  | INTERIM     | true",
      "HIGH_SPECIFIC | INTERIM     | true",
      "HIGH_GENERAL  | PROVISIONAL | false",
      "HIGH_SPECIFIC | PROVISIONAL | false",
      "STANDARD      | FINAL       | false",
      "STANDARD      | PROVISIONAL | false",
      "STANDARD      | INTERIM     | false",
      "NONE          | NONE        | false",
    ],
  )
  fun `shows a review due date only where the Overview card design does`(rating: String, stage: String, shown: Boolean) {
    val nextReviewDate = LocalDate.parse("2027-07-28")

    val expected = if (shown) nextReviewDate else null
    assertThat(reviewDueDateToShow(rating.toResult(), stage.toStage(), nextReviewDate)).isEqualTo(expected)
  }

  @Test
  fun `shows no review due date when there is no next review date`() {
    assertThat(reviewDueDateToShow(CsraResult.HIGH_GENERAL, CsraRatingStage.FINAL, null)).isNull()
  }

  @Test
  fun `uses an en dash, not a hyphen, as the designs do`() {
    CsraResult.entries.forEach { result ->
      CsraRatingStage.entries.forEach { stage ->
        assertThat(describeRating(result, stage)).doesNotContain("-")
      }
      assertThat(describeRatingShort(result)).doesNotContain("-")
    }
  }

  private fun String.toResult() = takeUnless { it == "NONE" }?.let { CsraResult.valueOf(it) }
  private fun String.toStage() = takeUnless { it == "NONE" }?.let { CsraRatingStage.valueOf(it) }
}
