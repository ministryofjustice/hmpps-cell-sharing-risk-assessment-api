package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult

class CsraRatingFilterTest {

  @Test
  fun `matches final and variant-specific high-risk filter values`() {
    assertThat(CsraRatingFilter.HIGH.matches(CsraResult.HIGH, CsraRatingStage.FINAL)).isTrue
    assertThat(CsraRatingFilter.HIGH_GENERAL.matches(CsraResult.HIGH_GENERAL, CsraRatingStage.FINAL)).isTrue
    assertThat(CsraRatingFilter.HIGH_SPECIFIC.matches(CsraResult.HIGH_SPECIFIC, CsraRatingStage.FINAL)).isTrue
    assertThat(CsraRatingFilter.HIGH_GENERAL_PROVISIONAL.matches(CsraResult.HIGH_GENERAL, CsraRatingStage.PROVISIONAL)).isTrue
    assertThat(CsraRatingFilter.HIGH_GENERAL_INTERIM.matches(CsraResult.HIGH_GENERAL, CsraRatingStage.INTERIM)).isTrue
    assertThat(CsraRatingFilter.HIGH_SPECIFIC_PROVISIONAL.matches(CsraResult.HIGH_SPECIFIC, CsraRatingStage.PROVISIONAL)).isTrue
  }

  @Test
  fun `rejects mismatched stages and legacy standard variants`() {
    assertThat(CsraRatingFilter.HIGH_GENERAL_PROVISIONAL.matches(CsraResult.HIGH_GENERAL, CsraRatingStage.FINAL)).isFalse
    assertThat(CsraRatingFilter.HIGH_GENERAL_INTERIM.matches(CsraResult.HIGH_GENERAL, CsraRatingStage.PROVISIONAL)).isFalse
    assertThat(CsraRatingFilter.HIGH_SPECIFIC_PROVISIONAL.matches(CsraResult.HIGH_SPECIFIC, CsraRatingStage.INTERIM)).isFalse
    assertThat(CsraRatingFilter.STANDARD.matches(CsraResult.STANDARD, CsraRatingStage.PROVISIONAL)).isFalse
    assertThat(CsraRatingFilter.STANDARD_LEGACY.matches(CsraResult.STANDARD, CsraRatingStage.FINAL)).isTrue
    assertThat(CsraRatingFilter.MED.matches(CsraResult.STANDARD, CsraRatingStage.FINAL)).isTrue
    assertThat(CsraRatingFilter.LOW.matches(CsraResult.STANDARD, CsraRatingStage.INTERIM)).isTrue
  }

  @Test
  fun `orders the distinct rating list in the UI sequence`() {
    val values = listOf(
      CsraRatingFilter.STANDARD,
      CsraRatingFilter.HIGH,
      CsraRatingFilter.HIGH_SPECIFIC_PROVISIONAL,
      CsraRatingFilter.HIGH_GENERAL,
      CsraRatingFilter.PEND,
      CsraRatingFilter.HIGH_GENERAL_INTERIM,
      CsraRatingFilter.STANDARD_LEGACY,
      CsraRatingFilter.MED,
      CsraRatingFilter.LOW,
      CsraRatingFilter.HIGH,
    )

    assertThat(CsraRatingFilter.ordered(values)).containsExactly(
      CsraRatingFilter.HIGH,
      CsraRatingFilter.HIGH_GENERAL,
      CsraRatingFilter.HIGH_GENERAL_INTERIM,
      CsraRatingFilter.HIGH_SPECIFIC_PROVISIONAL,
      CsraRatingFilter.STANDARD,
      CsraRatingFilter.STANDARD_LEGACY,
      CsraRatingFilter.LOW,
      CsraRatingFilter.MED,
      CsraRatingFilter.PEND,
    )
  }

  @Test
  fun `matches no rating and pending as expected`() {
    assertThat(CsraRatingFilter.NO_RATING.matches(null, null)).isTrue
    assertThat(CsraRatingFilter.NO_RATING.matches(CsraResult.STANDARD, CsraRatingStage.FINAL)).isFalse
    assertThat(CsraRatingFilter.PEND.matches(null, null)).isTrue
    assertThat(CsraRatingFilter.PEND.matches(CsraResult.STANDARD, CsraRatingStage.FINAL)).isFalse
  }
}
