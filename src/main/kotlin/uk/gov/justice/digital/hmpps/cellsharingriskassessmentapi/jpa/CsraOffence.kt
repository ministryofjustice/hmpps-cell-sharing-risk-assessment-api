package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One of the seven "is there any evidence of…" offence questions asked on an assessment stage.
 *
 * The answers themselves stay as the boolean columns on [CsraAssessmentStageEntity] — they are what the
 * mandatory-high rule reads and what reporting would filter on. This enum keys the evidence captured
 * *behind* a Yes ([CsraAssessmentStageOffenceEvidenceEntity]), so the values must stay in step with those
 * columns.
 */
@Schema(description = "An offence question asked on a CSRA assessment")
enum class CsraOffence {
  @Schema(description = "Murder, manslaughter or a life-threatening assault on another prisoner in custody")
  MURDER_MANSLAUGHTER,

  @Schema(description = "Assisting a suicide in custody")
  ASSISTING_SUICIDE,

  @Schema(description = "Sexual assault of a same-sex adult victim")
  SEXUAL_ASSAULT,

  @Schema(description = "Repeated violence in custody")
  REPEATED_VIOLENCE,

  @Schema(description = "Offending or behaviour motivated by prejudice")
  PREJUDICE_MOTIVATED,

  @Schema(description = "Arson or fire setting")
  ARSON,

  @Schema(description = "Kidnap, hostage taking or false imprisonment")
  KIDNAP_HOSTAGE,
}
