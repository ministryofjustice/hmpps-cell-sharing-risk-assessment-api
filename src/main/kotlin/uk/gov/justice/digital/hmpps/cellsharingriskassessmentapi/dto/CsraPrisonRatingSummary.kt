package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "CSRA rating counts across a prison's current roll")
data class CsraPrisonRatingSummary(
  @param:Schema(description = "The prison id", example = "LEI")
  val prisonId: String,
  @param:Schema(description = "The number of prisoners currently in the prison (the roll size)", example = "1015")
  val total: Int,
  @param:Schema(
    description = "Prisoners with no current CSRA rating: no CSRA record, or a latest assessment/review " +
      "with no rating saved yet",
    example = "3",
  )
  val noRating: Int,
  @param:Schema(
    description = "Prisoners whose current rating is high risk (any of High, High – general, High – specific)",
    example = "217",
  )
  val highRisk: Int,
  @param:Schema(description = "Prisoners whose current rating is standard risk", example = "795")
  val standardRisk: Int,
)
