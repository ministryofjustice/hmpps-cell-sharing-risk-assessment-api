package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import java.time.LocalDate

/*
 * The wording other services show for a CSRA rating, so they do not map our codes themselves (MAPA-515).
 *
 * Written for the DPS prisoner profile (MAPA-299 header, MAPA-303 Overview card) and kept to its designs,
 * en dash included. The wording depends on the rating and stage only, never on where the rating was
 * recorded: a High General recorded in NOMIS reads the same as one recorded here.
 */

const val NO_RATING_DESCRIPTION = "No rating"

/** The full wording, as on the profile Overview card: "High risk – general (interim)". */
fun describeRating(rating: CsraResult?, ratingStage: CsraRatingStage?): String {
  val base = when (rating) {
    null -> return NO_RATING_DESCRIPTION
    CsraResult.STANDARD -> "Standard risk"
    CsraResult.HIGH -> "High risk"
    CsraResult.HIGH_GENERAL -> "High risk – general"
    CsraResult.HIGH_SPECIFIC -> "High risk – specific"
  }
  return when (ratingStage) {
    CsraRatingStage.PROVISIONAL -> "$base (provisional)"
    CsraRatingStage.INTERIM -> "$base (interim)"
    CsraRatingStage.FINAL, null -> base
  }
}

/** The short wording, as in the profile header: "High – general". The stage is never shown. */
fun describeRatingShort(rating: CsraResult?): String = when (rating) {
  null -> NO_RATING_DESCRIPTION
  CsraResult.STANDARD -> "Standard"
  CsraResult.HIGH -> "High"
  CsraResult.HIGH_GENERAL -> "High – general"
  CsraResult.HIGH_SPECIFIC -> "High – specific"
}

/**
 * The review due date to show beside the rating, or null where the design shows none: Standard, No rating
 * and provisional ratings. A provisional rating is followed by its final stage, not a review, so a date
 * left over from an earlier rating would mislead.
 */
fun reviewDueDateToShow(rating: CsraResult?, ratingStage: CsraRatingStage?, nextReviewDate: LocalDate?): LocalDate? = when {
  rating == null || rating == CsraResult.STANDARD -> null
  ratingStage == CsraRatingStage.PROVISIONAL -> null
  else -> nextReviewDate
}
