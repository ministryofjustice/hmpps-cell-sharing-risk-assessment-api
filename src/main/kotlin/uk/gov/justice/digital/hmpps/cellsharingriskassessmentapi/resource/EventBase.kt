package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.resource

import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.RiskAssessment
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.AuditType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.CSRADomainEventType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.EventPublishAndAuditService
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.InformationSource

abstract class EventBase {

  @Autowired
  private lateinit var eventPublishAndAuditService: EventPublishAndAuditService

  protected fun eventPublishAndAudit(
    event: CSRADomainEventType,
    function: () -> RiskAssessment,
  ) = function().also { riskAssessment ->
    eventPublishAndAuditService.publishEvent(
      eventType = event,
      riskAssessment = riskAssessment,
      auditData = riskAssessment,
      source = InformationSource.DPS,
    )
  }

  protected fun audit(id: String, function: () -> Any) = function().also { auditData ->
    eventPublishAndAuditService.auditEvent(
      auditType = AuditType.CSRA_AMENDED,
      id = id,
      auditData = auditData,
    )
  }
}
