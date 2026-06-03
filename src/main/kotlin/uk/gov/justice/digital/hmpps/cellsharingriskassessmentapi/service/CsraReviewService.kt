package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.toDto
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewRepository
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CsraReviewService(
  private val csraReviewRepository: CsraReviewRepository,
) {
  fun getCsraReviewById(id: UUID): CsraReview? = csraReviewRepository.findByIdOrNull(id)?.toDto()
}
