package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.CsraReview
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.CsraType
import java.util.UUID

class CsraNomisSyncResourceTest : SqsIntegrationTestBase() {

  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

  // A single review mirroring the producer's payload (extra legacy fields are ignored on bind).
  private val reviewJson = """
    {
      "assessmentPrisonId": "LEI",
      "assessmentDate": "2025-11-22",
      "assessmentType": "CSR",
      "calculatedLevel": "STANDARD",
      "score": 1000,
      "status": "A",
      "committeeCode": "REVIEW",
      "nextReviewDate": "2026-05-22",
      "comment": "All good",
      "createdDateTime": "2025-12-06T12:34:56",
      "createdBy": "NQP56Y",
      "reviewLevel": "STANDARD",
      "approvedLevel": "HI",
      "evaluationDate": "2025-12-08",
      "reviewDetails": [
        { "code": "SEC1", "questions": [ { "code": "Q1", "responses": [ { "code": "R1", "answer": "Yes" } ] } ] }
      ]
    }
  """.trimIndent()

  @Nested
  inner class Migrate {
    @Test
    fun `persists each review and returns the mapped CSRA`() {
      val body = webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[$reviewJson]"))
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.length()").isEqualTo(1)
        .returnResult().responseBody!!

      val migrated = objectMapper.readValue(body, Array<CsraReview>::class.java).single()
      assertThat(migrated.prisonerNumber).isEqualTo("A1234BC")
      assertThat(migrated.prisonId).isEqualTo("LEI")
      assertThat(migrated.type).isEqualTo(CsraType.RATING)
      assertThat(migrated.finalResult).isEqualTo(CsraResult.HIGH)
      assertThat(migrated.finalResultDate).isEqualTo("2025-12-08")

      // and it is readable back via the read endpoint
      webTestClient.get().uri("/csra-review/${migrated.id}")
        .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.id").isEqualTo(migrated.id.toString())
        .jsonPath("$.finalResult").isEqualTo("HIGH")
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
    fun `creates a review when no csraReviewId is supplied then updates it when supplied`() {
      val createBody = webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.created").isEqualTo(true)
        .returnResult().responseBody!!
      val created = objectMapper.readValue(createBody, SyncResult::class.java)

      // sync again with the id -> update (200)
      val updateJson = reviewJson.replace("\"approvedLevel\": \"HI\"", "\"approvedLevel\": \"STANDARD\"")
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "csraReviewId": "${created.csraReviewId}", "review": $updateJson }"""))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.created").isEqualTo(false)
        .jsonPath("$.csraReviewId").isEqualTo(created.csraReviewId.toString())

      // the update is persisted: result changed and last-modified populated
      webTestClient.get().uri("/csra-review/${created.csraReviewId}")
        .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.finalResult").isEqualTo("STANDARD")
        .jsonPath("$.lastModifiedBy").isEqualTo("NQP56Y")
        .jsonPath("$.lastModifiedAt").isNotEmpty
    }

    @Test
    fun `returns 404 when updating an unknown csraReviewId`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "csraReviewId": "${UUID.randomUUID()}", "review": $reviewJson }"""))
        .exchange()
        .expectStatus().isNotFound
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
