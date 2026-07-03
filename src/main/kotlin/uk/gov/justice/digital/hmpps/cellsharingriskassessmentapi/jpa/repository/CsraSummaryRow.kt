package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

/**
 * A lightweight projection of a review used to compute the whole-history summary counts, without
 * loading full entities. The current rating is [finalResult] if present, otherwise [interimResult].
 */
data class CsraSummaryRow(
  val finalResult: CsraResult?,
  val interimResult: CsraResult?,
  val assessmentDate: LocalDate,
) {
  val result: CsraResult get() = finalResult ?: interimResult!!
}
