package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CellSharingRiskAssessmentApi

fun main(args: Array<String>) {
  runApplication<CellSharingRiskAssessmentApi>(*args)
}
