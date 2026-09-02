package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository

import org.springframework.data.jpa.domain.Specification
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraReviewStatus
import java.time.LocalDate

/** Builds the dynamic filter for a prisoner's CSRA history list. */
object CsraReviewSpecifications {
  fun history(
    prisonerNumber: String,
    from: LocalDate?,
    to: LocalDate?,
    prisons: List<String>?,
    results: List<CsraResult>?,
  ): Specification<CsraReviewEntity> = Specification { root, _, cb ->
    val currentResult = cb.coalesce(
      root.get<CsraResult>("finalResult"),
      root.get<CsraResult>("interimResult"),
    )
    val predicates = buildList {
      add(cb.equal(root.get<String>("prisonerNumber"), prisonerNumber))
      // Only reviews that carry a rating belong in the history; in-progress reviews with no result
      // are surfaced by the current-rating endpoint instead.
      add(cb.isNotNull(currentResult))
      // An archived review is hidden from the service (R-04). Stated explicitly rather than left to
      // the rating check above: archived rows are unrated only because the movement listener archives
      // only unrated work, and a cancelled review will carry an interim rating.
      add(cb.notEqual(root.get<CsraReviewStatus>("status"), CsraReviewStatus.ARCHIVED))
      from?.let { add(cb.greaterThanOrEqualTo(root.get<LocalDate>("assessmentDate"), it)) }
      to?.let { add(cb.lessThanOrEqualTo(root.get<LocalDate>("assessmentDate"), it)) }
      prisons?.takeIf { it.isNotEmpty() }?.let { add(root.get<String>("prisonId").`in`(it)) }
      results?.takeIf { it.isNotEmpty() }?.let { add(currentResult.`in`(it)) }
    }
    cb.and(*predicates.toTypedArray())
  }
}
