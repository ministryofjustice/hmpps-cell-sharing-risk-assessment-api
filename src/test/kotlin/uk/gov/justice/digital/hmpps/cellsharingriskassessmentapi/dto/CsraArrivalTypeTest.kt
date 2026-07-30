package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * What counts as an arrival, and how the four filter types are derived (MAPA-219). The `ADM` reason code
 * matters as much as the movement type: NOMIS normally records an inter-prison transfer in as an `ADM`
 * with reason `INT` rather than as a `TRN`.
 */
class CsraArrivalTypeTest {

  @ParameterizedTest
  @CsvSource(
    "ADM, N, NEW_ADMISSION",
    "ADM, , NEW_ADMISSION",
    "ADM, INT, TRANSFER_IN",
    "ADM, TRNCRT, TRANSFER_IN",
    "ADM, TRNTAP, TRANSFER_IN",
    "ADM, S, TRANSFER_IN",
    "ADM, Z, TRANSFER_IN",
    "TRN, , TRANSFER_IN",
    "CRT, , COURT_RETURN",
    "TAP, , TEMPORARY_ABSENCE_RETURN",
  )
  fun `maps a movement to its arrival type`(movementType: String, reasonCode: String?, expected: CsraArrivalType) {
    assertThat(CsraArrivalType.fromMovement(movementType, reasonCode)).isEqualTo(expected)
  }

  @Test
  fun `a transfer from a foreign prison is a new arrival into the estate, not a transfer in`() {
    assertThat(CsraArrivalType.fromMovement("ADM", "T")).isEqualTo(CsraArrivalType.NEW_ADMISSION)
  }

  @ParameterizedTest
  @ValueSource(strings = ["REL", "TRNCRT", "UNKNOWN", ""])
  fun `is not an arrival for any other movement type`(movementType: String) {
    assertThat(CsraArrivalType.fromMovement(movementType, null)).isNull()
  }

  @Test
  fun `the declaration order is the order the filter is shown in`() {
    assertThat(CsraArrivalType.entries).containsExactly(
      CsraArrivalType.NEW_ADMISSION,
      CsraArrivalType.TRANSFER_IN,
      CsraArrivalType.COURT_RETURN,
      CsraArrivalType.TEMPORARY_ABSENCE_RETURN,
    )
  }
}
