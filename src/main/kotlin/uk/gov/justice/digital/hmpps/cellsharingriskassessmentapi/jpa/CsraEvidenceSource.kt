package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Where the evidence for an offence answer was found ("Where did you find evidence of…?").
 *
 * The first four mirror the stage-level "which evidence sources have you checked?" flags on
 * [CsraAssessmentStageEntity], but the two questions are deliberately distinct: those record which sources
 * the assessor *reviewed*, these record where a specific piece of evidence *came from*. [OTHER] exists only
 * here, and carries free text naming the source.
 */
@Schema(description = "Where the evidence for an offence answer was found")
enum class CsraEvidenceSource {
  @Schema(description = "PNC — current and previous convictions")
  PNC,

  @Schema(description = "Warrant — current charge or offence")
  WARRANT,

  @Schema(description = "DPS — current and historical adjudications")
  DPS,

  @Schema(description = "PER — violent behaviours in prison, court or PECS custody")
  PER,

  @Schema(description = "Another source, named in the accompanying free text")
  OTHER,
}
