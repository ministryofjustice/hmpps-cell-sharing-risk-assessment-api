package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraAssessmentStageOffenceEvidenceEntity
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraEvidenceSource
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraOffence

/**
 * The evidence behind a Yes to one of the seven offence questions — the "Where did you find evidence of…?"
 * screen.
 *
 * The sources are a set on the wire but individual boolean columns in the database. The set maps directly
 * onto the screen's checkbox group, while the columns keep the evidence table narrow and queryable without
 * a third level of child table for a fixed list of five.
 */
@Schema(description = "The evidence behind a Yes to an offence question")
data class CsraOffenceEvidence(
  @param:Schema(description = "The offence question this evidence relates to", example = "MURDER_MANSLAUGHTER", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotNull
  val offence: CsraOffence,

  @param:Schema(description = "Where the evidence was found. Include OTHER to name a source in otherSourceDetail.", example = "[\"PNC\", \"DPS\"]")
  val sources: Set<CsraEvidenceSource> = emptySet(),

  @param:Schema(description = "Names the source when OTHER is selected", example = "Police intelligence report")
  val otherSourceDetail: String? = null,

  @param:Schema(description = "Free text describing the evidence found", example = "Convicted of manslaughter of a fellow prisoner in 2019.")
  val details: String? = null,
)

fun CsraOffenceEvidence.toEntity(stage: CsraAssessmentStageEntity) = CsraAssessmentStageOffenceEvidenceEntity(
  stage = stage,
  offence = offence,
  pnc = CsraEvidenceSource.PNC in sources,
  warrant = CsraEvidenceSource.WARRANT in sources,
  dps = CsraEvidenceSource.DPS in sources,
  per = CsraEvidenceSource.PER in sources,
  other = CsraEvidenceSource.OTHER in sources,
  otherSourceDetail = otherSourceDetail,
  details = details,
)

fun CsraAssessmentStageOffenceEvidenceEntity.toDto() = CsraOffenceEvidence(
  offence = offence,
  sources = buildSet {
    if (pnc) add(CsraEvidenceSource.PNC)
    if (warrant) add(CsraEvidenceSource.WARRANT)
    if (dps) add(CsraEvidenceSource.DPS)
    if (per) add(CsraEvidenceSource.PER)
    if (other) add(CsraEvidenceSource.OTHER)
  },
  otherSourceDetail = otherSourceDetail,
  details = details,
)
