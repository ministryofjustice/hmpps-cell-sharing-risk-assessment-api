package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of arrival into a prison, for the "recent arrivals" screen and its filter.
 *
 * Callers do not need to know NOMIS movement types — [fromMovement] works out what counts as an arrival.
 */
@Schema(description = "The type of arrival into a prison")
enum class CsraArrivalType {
  // Declaration order is the order the filter checkboxes are shown in, because the arrival-type counts
  // are built from `entries`.
  NEW_ADMISSION,
  TRANSFER_IN,
  COURT_RETURN,
  TEMPORARY_ABSENCE_RETURN,
  ;

  companion object {
    /**
     * NOMIS admission reasons that mean the prisoner came from another establishment rather than
     * arriving in custody afresh. `T` (transfer in from a foreign prison) is deliberately absent — that
     * is a new arrival into the estate.
     */
    private val TRANSFER_IN_REASONS = setOf(
      "INT", // Transfer In from Other Establishment
      "TRNCRT", // Transfer via court
      "TRNTAP", // Transfer via temporary release
      "S", // Overnight stopover before transfer to establishment
      "Z", // Same-day stopover en route to another establishment
    )

    /**
     * Maps a NOMIS IN movement to an arrival type, or null when it is not an arrival at all.
     *
     * All four movement types the screen cares about count as arrivals (MAPA-219): a return from court
     * or temporary absence brings someone back onto the wing and so may need a CSRA, even though it
     * resumes a stay they were already in the middle of.
     *
     * `ADM` is refined by its reason code as well as its type. NOMIS normally records an inter-prison
     * transfer in as an `ADM` with reason `INT` rather than as a `TRN` — mapping on type alone would
     * report those as new admissions and leave the transfers-in count at zero. Handling both means the
     * split is right whichever way prison-api reports it.
     */
    fun fromMovement(movementType: String, movementReasonCode: String?): CsraArrivalType? = when (movementType) {
      // Until prison-api supplies the reason code, an admission reads as a new arrival.
      "ADM" -> if (movementReasonCode in TRANSFER_IN_REASONS) TRANSFER_IN else NEW_ADMISSION
      "TRN" -> TRANSFER_IN
      "CRT" -> COURT_RETURN
      "TAP" -> TEMPORARY_ABSENCE_RETURN
      else -> null
    }
  }
}
