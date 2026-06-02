package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CsraReviewService {
  fun getCsraReviewById(id: UUID): CsraReview = CsraReview(id = id)
}
