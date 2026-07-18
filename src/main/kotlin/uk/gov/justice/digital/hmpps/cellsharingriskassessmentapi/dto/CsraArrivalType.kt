package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of arrival into a prison, for the "recent arrivals" screen and its filter. Mapped from the
 * NOMIS movement type of an IN-direction external movement.
 */
@Schema(description = "The type of arrival into a prison")
enum class CsraArrivalType {
  NEW_ADMISSION,
  TRANSFER_IN,
  COURT_RETURN,
  TEMPORARY_ABSENCE_RETURN,
  ;

  companion object {
    /** Maps a NOMIS IN movement type (ADM/TRN/CRT/TAP) to an arrival type, or null if not an arrival we show. */
    fun fromMovementType(movementType: String): CsraArrivalType? = when (movementType) {
      "ADM" -> NEW_ADMISSION
      "TRN" -> TRANSFER_IN
      "CRT" -> COURT_RETURN
      "TAP" -> TEMPORARY_ABSENCE_RETURN
      else -> null
    }
  }
}
