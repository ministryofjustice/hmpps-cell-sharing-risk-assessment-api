package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewAnswers
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReviewStageRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewReason
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf

/**
 * Guards against a question being added to the answers without being added to the question list, which
 * would leave it out of every rule: details on a Yes, the question sets and the mandatory-high check.
 */
class CsraReviewQuestionSetTest {

  @Test
  fun `every yes or no answer on a review, apart from the multidisciplinary team answer, is a listed question`() {
    val answerFields = CsraReviewAnswers::class.memberProperties
      .filter { it.returnType == typeOf<Boolean?>() }
      .map { it.name }
      .filter { it != "mdtReviewed" }

    assertThat(CsraReviewQuestion.entries.map { it.field }).containsExactlyInAnyOrderElementsOf(answerFields)
  }

  @Test
  fun `every details field on a review belongs to a listed question`() {
    val detailFields = CsraReviewAnswers::class.memberProperties
      .filter { it.returnType == typeOf<String?>() }
      .map { it.name }

    assertThat(CsraReviewQuestion.entries.map { it.detail.name }).containsExactlyInAnyOrderElementsOf(detailFields)
    CsraReviewQuestion.entries.forEach { assertThat(it.detail.name).isEqualTo("${it.field}Detail") }
  }

  @Test
  fun `the confirm request carries every saved answer under the same name, so saved answers can be sent with it`() {
    val requestFields = CsraReviewStageRequest::class.memberProperties.associate { it.name to it.returnType }

    CsraReviewAnswers::class.memberProperties.forEach {
      assertThat(requestFields[it.name]).describedAs(it.name).isEqualTo(it.returnType)
    }
  }

  @Test
  fun `every question is asked by at least one reason for a review`() {
    val asked = CsraReviewReason.entries.flatMap { CsraReviewQuestionSet.forReason(it).asked }.toSet()

    assertThat(asked).containsExactlyInAnyOrderElementsOf(CsraReviewQuestion.entries)
  }

  @Test
  fun `a mandatory high-risk trigger is always a question its reason requires`() {
    CsraReviewReason.entries.map { CsraReviewQuestionSet.forReason(it) }.forEach {
      assertThat(it.required).containsAll(it.mandatoryHighTriggers)
    }
  }
}
