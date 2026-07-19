package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraCurrentRating
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service.InformationSource

/**
 * The rules for what reaches the domain events topic (MAPA-141):
 * an unrated CSRA is still a draft and must never be announced, and every event that is published must
 * say whether the change came from DPS or NOMIS so the sync service does not echo its own writes back.
 *
 * Publication is asynchronous, so "nothing was published" is asserted by performing the silent action and
 * then a known-noisy one, and checking the noisy event is the only thing on the queue.
 */
class CsraEventPublishingTest : SqsIntegrationTestBase() {

  private val writeRole = listOf("ROLE_CSRA_REVIEW__RW")
  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

  private fun stageBody(rating: String) = """
    {
      "rating": "$rating",
      "prisonId": "LEI",
      "assessmentComment": "Assessment comment"
    }
  """.trimIndent()

  /** [calculatedLevel] of PEND with no review/approved level is a NOMIS review that carries no rating. */
  private fun reviewJson(calculatedLevel: String = "STANDARD", approvedLevel: String? = "STANDARD") = """
    {
      "bookingId": 1234567,
      "nomisSequence": 1,
      "assessmentPrisonId": "LEI",
      "assessmentDate": "2025-11-22",
      "assessmentType": "CSR",
      "calculatedLevel": "$calculatedLevel",
      ${approvedLevel?.let { """"approvedLevel": "$it",""" } ?: ""}
      "score": 1000,
      "status": "A",
      "nextReviewDate": "2026-05-22",
      "createdDateTime": "2025-12-06T12:34:56",
      "createdBy": "NQP56Y",
      "reviewDetails": []
    }
  """.trimIndent()

  private fun start(prisonerNumber: String): CsraCurrentRating = webTestClient.post()
    .uri("/csra-review/prisoner/$prisonerNumber/assessment")
    .headers(setAuthorisation(roles = writeRole))
    .exchange()
    .expectStatus().isCreated
    .expectBody<CsraCurrentRating>()
    .returnResult().responseBody!!

  private fun submitProvisional(prisonerNumber: String, assessmentId: Any, rating: String = "STANDARD") {
    webTestClient.put().uri("/csra-review/prisoner/$prisonerNumber/assessment/$assessmentId/provisional")
      .headers(setAuthorisation(roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(stageBody(rating)))
      .exchange()
      .expectStatus().isOk
  }

  private fun sync(prisonerNumber: String, review: String): SyncResult = webTestClient.post()
    .uri("/nomis-sync/sync/$prisonerNumber")
    .headers(setAuthorisation(roles = syncRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue("""{ "review": $review }"""))
    .exchange()
    .expectStatus().isCreated
    .expectBody<SyncResult>()
    .returnResult().responseBody!!

  private fun migrate(prisonerNumber: String, review: String) {
    webTestClient.post().uri("/nomis-sync/migrate/$prisonerNumber")
      .headers(setAuthorisation(roles = syncRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue("[$review]"))
      .exchange()
      .expectStatus().isCreated
  }

  @Test
  fun `starting an assessment publishes nothing until a rating is saved`() {
    val prisoner = "E1111EE"
    val assessmentId = start(prisoner).reviewId!!

    // The draft exists but nothing has been announced; the provisional rating below is the first event.
    submitProvisional(prisoner, assessmentId)

    val events = getDomainEvents(1)
    assertThat(events).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo("cell.sharing.risk.assessment.created")
      assertThat(it.additionalInformation?.id).isEqualTo(assessmentId)
    })
  }

  @Test
  fun `a DPS rating is published with the prisoner number and a DPS source`() {
    val prisoner = "E2222EE"
    val assessmentId = start(prisoner).reviewId!!

    submitProvisional(prisoner, assessmentId, rating = "HIGH_GENERAL")

    val event = getDomainEvents(1).single()
    assertThat(event.additionalInformation?.nomsNumber).isEqualTo(prisoner)
    assertThat(event.additionalInformation?.source).isEqualTo(InformationSource.DPS)
  }

  @Test
  fun `a synchronised NOMIS rating is published with a NOMIS source`() {
    val prisoner = "E3333EE"

    val result = sync(prisoner, reviewJson())

    val event = getDomainEvents(1).single()
    assertThat(event.eventType).isEqualTo("cell.sharing.risk.assessment.created")
    assertThat(event.additionalInformation?.id).isEqualTo(result.csraReviewId)
    assertThat(event.additionalInformation?.nomsNumber).isEqualTo(prisoner)
    assertThat(event.additionalInformation?.source).isEqualTo(InformationSource.NOMIS)
  }

  @Test
  fun `synchronising a NOMIS review with no rating publishes nothing`() {
    val prisoner = "E4444EE"

    sync(prisoner, reviewJson(calculatedLevel = "PEND", approvedLevel = null))
    // A rated sync for a different prisoner proves the unrated one really was silent.
    val rated = sync("E4445EE", reviewJson())

    val event = getDomainEvents(1).single()
    assertThat(event.additionalInformation?.id).isEqualTo(rated.csraReviewId)
  }

  @Test
  fun `a bulk migration publishes nothing`() {
    migrate("E5555EE", reviewJson())
    val rated = sync("E5556EE", reviewJson())

    val event = getDomainEvents(1).single()
    assertThat(event.additionalInformation?.id).isEqualTo(rated.csraReviewId)
  }
}
