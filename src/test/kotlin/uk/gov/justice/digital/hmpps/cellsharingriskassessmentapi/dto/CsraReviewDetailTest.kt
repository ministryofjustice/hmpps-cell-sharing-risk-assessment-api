package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraQuestionDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraResponseDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraReviewDetailDto

class CsraReviewDetailTest {

  private fun response(answer: String?) = CsraResponseDto(code = "R", answer = answer)

  private fun question(code: String, description: String?, vararg answers: String?) = CsraQuestionDto(code = code, description = description, responses = answers.map(::response))

  private fun section(code: String, vararg questions: CsraQuestionDto) = CsraReviewDetailDto(code = code, questions = questions.toList())

  @Test
  fun `no sections produces no questions`() {
    assertThat(emptyList<CsraReviewDetailDto>().toReviewQuestions()).isEmpty()
  }

  @Test
  fun `a section with no questions contributes nothing`() {
    assertThat(listOf(section("SEC1")).toReviewQuestions()).isEmpty()
  }

  @Test
  fun `a question with no responses is kept with no answer`() {
    // Mirrors the legacy contract, which lists the question and leaves the answer empty. Hiding it is the
    // consumer's choice, not ours.
    val questions = listOf(section("SEC1", question("Q1", "Unanswered"))).toReviewQuestions()

    assertThat(questions).singleElement().satisfies({
      assertThat(it.question).isEqualTo("Unanswered")
      assertThat(it.answer).isNull()
      assertThat(it.additionalAnswers).isEmpty()
    })
  }

  @Test
  fun `null answers are skipped so the first real answer is not lost`() {
    val questions = listOf(section("SEC1", question("Q1", "Concerns", null, "Kept", null, "Also kept"))).toReviewQuestions()

    assertThat(questions.single().answer).isEqualTo("Kept")
    assertThat(questions.single().additionalAnswers).containsExactly("Also kept")
  }

  @Test
  fun `question text falls back to the code when NOMIS supplied none`() {
    assertThat(listOf(section("SEC1", question("QCODE", null, "Yes"))).toReviewQuestions().single().question)
      .isEqualTo("QCODE")
  }

  @Test
  fun `questions keep their stored order across sections`() {
    val questions = listOf(
      section("SEC1", question("Q1", "First", "a"), question("Q2", "Second", "b")),
      section("SEC2", question("Q3", "Third", "c")),
    ).toReviewQuestions()

    assertThat(questions.map { it.question }).containsExactly("First", "Second", "Third")
  }

  @Test
  fun `the same question code in two sections is kept twice`() {
    // No dedupe: NOMIS can ask the same question in more than one section, and collapsing them would drop
    // an answer the assessor actually gave.
    val questions = listOf(
      section("SEC1", question("Q1", "Repeated", "from section one")),
      section("SEC2", question("Q1", "Repeated", "from section two")),
    ).toReviewQuestions()

    assertThat(questions).hasSize(2)
    assertThat(questions.map { it.answer }).containsExactly("from section one", "from section two")
  }
}
