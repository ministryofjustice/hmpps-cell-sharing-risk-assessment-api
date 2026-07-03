package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A group that a high-risk prisoner may pose a risk to, captured by the "Who is this person a risk to?"
 * multi-select on a high-risk assessment stage. [NONE] represents "no identified risk to any of these
 * groups".
 */
@Schema(description = "A group a high-risk prisoner may pose a risk to")
enum class CsraRiskToCategory {
  DIFFERENT_ETHNICITY,
  DIFFERENT_RELIGION,
  DISABLED,
  GANG_MEMBERS,
  SEXUAL_MINORITY,
  OLD_PEOPLE,
  SPECIFIC_PERSONS,
  TRANSGENDER,
  OTHER,
  NONE,
}
