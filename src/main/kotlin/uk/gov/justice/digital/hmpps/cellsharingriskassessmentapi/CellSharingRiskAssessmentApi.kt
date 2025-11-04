package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

const val SYSTEM_USERNAME = "CELL_SHARING_RISK_ASSESSMENT_API"

@SpringBootApplication
class CellSharingRiskAssessmentApi

fun main(args: Array<String>) {
  runApplication<CellSharingRiskAssessmentApi>(*args)
}
