package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraRiskToDetail
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraVulnerabilityDetail
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraRiskToCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraVulnerabilityCategory
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource.CsraRiskCategoriesInvalidException

/**
 * The "who is this person a risk to / are they vulnerable" rules, shared by the assessment and review
 * journeys because both ask the same two questions of the same two child tables.
 *
 * Two rules, neither previously enforced:
 *
 * 1. **NONE is exclusive.** Both category enums carry a NONE member meaning "no identified risk to any of
 *    these groups" / "no identified vulnerabilities". The user must tick it actively, so it is the answer
 *    rather than the absence of one — and it cannot coexist with a real category.
 * 2. **HIGH_SPECIFIC requires an answer to both, and every other rating requires neither.** The questions
 *    are only asked for a High risk - specific rating. Without this, a HIGH_SPECIFIC rating could be saved
 *    with no groups recorded at all, indistinguishable from one where the reviewer ticked NONE.
 */
@Component
class CsraRiskCategoryValidator {

  fun validate(rating: CsraResult, riskTo: List<CsraRiskToDetail>, vulnerabilities: List<CsraVulnerabilityDetail>) {
    validateNoneIsExclusive(riskTo.map { it.category }, CsraRiskToCategory.NONE, "riskTo")
    validateNoneIsExclusive(vulnerabilities.map { it.category }, CsraVulnerabilityCategory.NONE, "vulnerabilities")

    if (rating == CsraResult.HIGH_SPECIFIC) {
      if (riskTo.isEmpty()) {
        throw CsraRiskCategoriesInvalidException("riskTo is required for a HIGH_SPECIFIC rating; use NONE for no identified risk")
      }
      if (vulnerabilities.isEmpty()) {
        throw CsraRiskCategoriesInvalidException("vulnerabilities is required for a HIGH_SPECIFIC rating; use NONE for no identified vulnerabilities")
      }
      return
    }

    if (riskTo.isNotEmpty() || vulnerabilities.isNotEmpty()) {
      throw CsraRiskCategoriesInvalidException("riskTo and vulnerabilities are only accepted for a HIGH_SPECIFIC rating, not $rating")
    }
  }

  private fun <T> validateNoneIsExclusive(categories: List<T>, none: T, field: String) {
    if (none in categories && categories.size > 1) {
      throw CsraRiskCategoriesInvalidException("$field cannot combine NONE with another category")
    }
  }
}
