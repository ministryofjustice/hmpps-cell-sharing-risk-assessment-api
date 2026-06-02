package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyInserters
import java.util.UUID

class CsraNomisSyncResourceTest : SqsIntegrationTestBase() {

  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

  // A single review mirroring the producer's CsraReviewDto so we guard the binding contract.
  private val reviewJson = """
    {
      "bookingId": 3222111,
      "sequenceNumber": 4,
      "assessmentDate": "2025-11-22",
      "assessmentType": "CSR",
      "calculatedLevel": "STANDARD",
      "score": 1000,
      "status": "A",
      "staffId": 123456,
      "committeeCode": "REVIEW",
      "nextReviewDate": "2026-05-22",
      "comment": "All good",
      "placementPrisonId": "LEI",
      "createdDateTime": "2025-12-06T12:34:56",
      "createdBy": "NQP56Y",
      "reviewLevel": "STANDARD",
      "approvedLevel": "STANDARD",
      "evaluationDate": "2025-12-08",
      "evaluationResultCode": "APP",
      "reviewCommitteeCode": "REVIEW",
      "reviewCommitteeComment": "Approved",
      "reviewPlacementPrisonId": "LEI",
      "reviewComment": "Reviewed",
      "reviewDetails": [
        {
          "code": "SEC1",
          "description": "Section one",
          "questions": [
            {
              "code": "Q1",
              "description": "Question one",
              "responses": [
                { "code": "R1", "answer": "Yes", "comment": "ok" }
              ]
            }
          ]
        }
      ]
    }
  """.trimIndent()

  @Nested
  inner class Migrate {
    @Test
    fun `returns 201 with a DPS id mapping per review`() {
      webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[$reviewJson]"))
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.length()").isEqualTo(1)
        .jsonPath("$[0].id").isNotEmpty
    }

    @Test
    fun `returns 400 for an invalid enum value`() {
      val badJson = reviewJson.replace("\"assessmentType\": \"CSR\"", "\"assessmentType\": \"NOPE\"")
      webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[$badJson]"))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 401 without a token`() {
      webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[$reviewJson]"))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[$reviewJson]"))
        .exchange()
        .expectStatus().isForbidden
    }
  }

  @Nested
  inner class Sync {
    @Test
    fun `returns 201 and created=true when no csraReviewId is supplied`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.created").isEqualTo(true)
        .jsonPath("$.csraReviewId").isNotEmpty
    }

    @Test
    fun `returns 200 and created=false when a csraReviewId is supplied`() {
      val existingId = UUID.randomUUID().toString()
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "csraReviewId": "$existingId", "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.created").isEqualTo(false)
        .jsonPath("$.csraReviewId").isEqualTo(existingId)
    }

    @Test
    fun `returns 400 when the review is missing`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("{}"))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 401 without a token`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isForbidden
    }
  }
}
