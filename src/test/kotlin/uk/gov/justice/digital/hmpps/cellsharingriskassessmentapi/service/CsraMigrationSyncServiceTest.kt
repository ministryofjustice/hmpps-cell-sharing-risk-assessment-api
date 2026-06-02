package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraAssessmentType
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraSyncRequest
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.NomisCsraReview
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CsraMigrationSyncServiceTest {

  // The real client is a no-op unless the Application Insights java agent rewrites it at runtime
  private val service = CsraMigrationSyncService(TelemetryClient())

  private fun review(count: Long) = NomisCsraReview(
    assessmentDate = LocalDate.parse("2025-11-22").plusDays(count),
    assessmentType = CsraAssessmentType.CSR,
    score = BigDecimal("1000"),
    status = CsraStatus.A,
    createdDateTime = LocalDateTime.parse("2025-12-06T12:34:56"),
    createdBy = "NQP56Y",
  )

  @Test
  fun `migrate returns one result per review carrying the NOMIS ids and a generated DPS id`() {
    val reviews = listOf(review(1), review(2))

    val results = service.migrate("A1234BC", reviews)

    assertThat(results).hasSize(2)
    assertThat(results).allSatisfy {
      assertThat(it.id).isNotNull()
    }
    assertThat(results.map { it.id }.toSet()).hasSize(2)
  }

  @Test
  fun `migrate of an empty list returns no results`() {
    assertThat(service.migrate("A1234BC", emptyList())).isEmpty()
  }

  @Test
  fun `sync without a csraReviewId is treated as a create and generates a new id`() {
    val result = service.sync("A1234BC", CsraSyncRequest(review = review(1)))

    assertThat(result.created).isTrue()
    assertThat(result.csraReviewId).isNotNull()
  }

  @Test
  fun `sync with a csraReviewId is treated as an update and echoes the id`() {
    val existingId = UUID.randomUUID()

    val result = service.sync("A1234BC", CsraSyncRequest(csraReviewId = existingId, review = review(1)))

    assertThat(result.created).isFalse()
    assertThat(result.csraReviewId).isEqualTo(existingId)
  }
}
