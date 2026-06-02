package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import java.time.Clock

@Service
class EventPublishAndAuditService(
  private val snsService: SnsService,
  private val auditService: AuditService,
  private val clock: Clock,
) {
  fun publishEvent(
    eventType: CSRADomainEventType,
    csraReview: CsraReview,
    auditData: Any? = null,
    source: InformationSource = InformationSource.DPS,
  ) {
    auditData?.let {
      auditEvent(
        auditType = eventType.auditType,
        id = csraReview.id.toString(),
        auditData = it,
        source = source,
      )
    }
  }

  fun auditEvent(
    auditType: AuditType,
    id: String,
    auditData: Any,
    source: InformationSource = InformationSource.DPS,
  ) {
    auditService.sendMessage(
      auditType = auditType,
      id = id,
      details = auditData,
    )
  }
}

enum class InformationSource {
  DPS,
  NOMIS,
}
