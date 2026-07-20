package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The type of arrival into a prison, for the "recent arrivals" screen and its filter.
 *
 * Callers do not need to know NOMIS movement types — [fromMovement] works out what counts as an arrival.
 */
@Schema(description = "The type of arrival into a prison")
enum class CsraArrivalType {
  NEW_ADMISSION,
  TRANSFER_IN,
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
     * Only an `ADM` movement means the prisoner has arrived: this mirrors how prison-api itself decides
     * a prisoner has entered a prison (`Offender.getPrisonerInPrisonSummary`, behind the prison
     * timeline). A return from court (`CRT`) or from temporary absence (`TAP`) resumes a stay the
     * prisoner was already in the middle of, so it is not an arrival. `TRN` only ever exists as the
     * out-leg of a transfer — the in-leg at the receiving prison is an `ADM`.
     */
    fun fromMovement(movementType: String, movementReasonCode: String?): CsraArrivalType? {
      if (movementType != "ADM") return null
      // Until prison-api supplies the reason code, an admission reads as a new arrival.
      return if (movementReasonCode in TRANSFER_IN_REASONS) TRANSFER_IN else NEW_ADMISSION
    }
  }
}
