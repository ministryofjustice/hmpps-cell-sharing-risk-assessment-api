package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraCommitteeCode
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraLevel
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraMigrationResponse
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.CsraStatus
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.dto.migration.SyncResult
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraNextReviewRepository
import uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.jpa.repository.CsraReviewNomisRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CsraNomisSyncResourceTest : SqsIntegrationTestBase() {

  @Autowired
  private lateinit var csraReviewNomisRepository: CsraReviewNomisRepository

  @Autowired
  private lateinit var csraNextReviewRepository: CsraNextReviewRepository

  private val syncRole = listOf("ROLE_PRISONER_CSRA__SYNC__RW")

  // A single review mirroring the producer's payload (extra legacy fields are ignored on bind).
  private fun reviewJson(
    bookingId: Long = 1234567,
    nomisSequence: Int = 1,
    nextReviewDate: String = "2026-05-22",
    assessmentDate: String = "2025-11-22",
  ) = """
    {
      "bookingId": $bookingId,
      "nomisSequence": $nomisSequence,
      "assessmentPrisonId": "LEI",
      "assessmentDate": "$assessmentDate",
      "assessmentType": "CSR",
      "calculatedLevel": "STANDARD",
      "score": 1000,
      "status": "A",
      "committeeCode": "REVIEW",
      "nextReviewDate": "$nextReviewDate",
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
    fun `persists each review and returns the ids`() {
      val migrated = webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[${reviewJson(10,1,"2026-05-01")},${reviewJson(10,2,"2026-05-02")},${reviewJson(11,3,"2026-05-03")}]"))
        .exchange()
        .expectStatus().isCreated
        .expectBody<List<CsraMigrationResponse>>()
        .returnResult().responseBody!!

      assertThat(migrated.size).isEqualTo(3)
      assertThat(migrated[0].id).isNotNull()
      assertThat(migrated[0].bookingId).isEqualTo(10)
      assertThat(migrated[0].nomisSequence).isEqualTo(1)

      assertThat(migrated[1].id).isNotNull()
      assertThat(migrated[1].bookingId).isEqualTo(10)
      assertThat(migrated[1].nomisSequence).isEqualTo(2)

      assertThat(migrated[2].id).isNotNull()
      assertThat(migrated[2].bookingId).isEqualTo(11)
      assertThat(migrated[2].nomisSequence).isEqualTo(3)

      // Check data is readable back via the read endpoint
      webTestClient.get().uri("/csra-review/${migrated[0].id}")
        .headers(setAuthorisation(roles = listOf("ROLE_CSRA_REVIEW__R")))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.id").isEqualTo(migrated[0].id.toString())
        .jsonPath("$.prisonerNumber").isEqualTo("A1234BC")
        .jsonPath("$.prisonId").isEqualTo("LEI")
        .jsonPath("$.type").isEqualTo("RATING")
        .jsonPath("$.finalResult").isEqualTo("HIGH")
        .jsonPath("$.finalResultDate").isEqualTo("2025-12-08")

      // the prisoner's single next review date comes from their latest review (same assessment date, so
      // the last review migrated wins the tie)
      val nextReview = csraNextReviewRepository.findByPrisonerNumber("A1234BC")!!
      assertThat(nextReview.nextReviewDate).isEqualTo(LocalDate.parse("2026-05-03"))
      assertThat(nextReview.setByReviewId).isEqualTo(migrated[2].id)

      // the additional NOMIS data, including the Q&A blob, is persisted alongside each core review
      val nomis = csraReviewNomisRepository.findByCsraReviewId(migrated[0].id)!!
      assertThat(nomis.score).isEqualByComparingTo(BigDecimal("1000"))
      assertThat(nomis.status).isEqualTo(CsraStatus.A)
      assertThat(nomis.calculatedLevel).isEqualTo(CsraLevel.STANDARD)
      assertThat(nomis.approvedLevel).isEqualTo(CsraLevel.HI)
      assertThat(nomis.committeeCode).isEqualTo(CsraCommitteeCode.REVIEW)
      assertThat(nomis.comment).isEqualTo("All good")
      assertThat(nomis.reviewDetails).singleElement()
        .satisfies({ section -> assertThat(section.code).isEqualTo("SEC1") })
      assertThat(csraReviewNomisRepository.findByCsraReviewId(migrated[2].id)).isNotNull()
    }

    @Test
    fun `returns 400 for an invalid enum value`() {
      val badJson = reviewJson().replace("\"assessmentType\": \"CSR\"", "\"assessmentType\": \"NOPE\"")
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
        .body(BodyInserters.fromValue("[${reviewJson()}]"))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.post().uri("/nomis-sync/migrate/A1234BC")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("[${reviewJson()}]"))
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
        .body(BodyInserters.fromValue("""{ "review": ${reviewJson()} }"""))
        .exchange()
        .expectStatus().isCreated
        .expectBody()
        .jsonPath("$.created").isEqualTo(true)
        .returnResult().responseBody!!
      val created = objectMapper.readValue(createBody, SyncResult::class.java)

      // sync again with the id -> update (200)
      val updateJson = reviewJson().replace("\"approvedLevel\": \"HI\"", "\"approvedLevel\": \"STANDARD\"")
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

      // the adjacent NOMIS record was created on sync-create and refreshed on sync-update
      val nomis = csraReviewNomisRepository.findByCsraReviewId(created.csraReviewId)!!
      assertThat(nomis.approvedLevel).isEqualTo(CsraLevel.STANDARD)
      assertThat(nomis.reviewDetails).singleElement()
        .satisfies({ section -> assertThat(section.code).isEqualTo("SEC1") })
    }

    @Test
    fun `only the prisoner's latest review sets the current next review date`() {
      fun sync(nextReviewDate: String, assessmentDate: String) {
        webTestClient.post().uri("/nomis-sync/sync/B2222BB")
          .headers(setAuthorisation(roles = syncRole))
          .contentType(MediaType.APPLICATION_JSON)
          .body(BodyInserters.fromValue("""{ "review": ${reviewJson(nextReviewDate = nextReviewDate, assessmentDate = assessmentDate)} }"""))
          .exchange()
          .expectStatus().isCreated
      }

      // first review sets it
      sync(nextReviewDate = "2026-07-10", assessmentDate = "2026-01-10")
      assertThat(csraNextReviewRepository.findByPrisonerNumber("B2222BB")!!.nextReviewDate)
        .isEqualTo(LocalDate.parse("2026-07-10"))

      // an out-of-order sync of an older review must not overwrite it
      sync(nextReviewDate = "2025-11-01", assessmentDate = "2025-05-01")
      assertThat(csraNextReviewRepository.findByPrisonerNumber("B2222BB")!!.nextReviewDate)
        .isEqualTo(LocalDate.parse("2026-07-10"))

      // a newer review does overwrite it
      sync(nextReviewDate = "2026-09-01", assessmentDate = "2026-03-01")
      assertThat(csraNextReviewRepository.findByPrisonerNumber("B2222BB")!!.nextReviewDate)
        .isEqualTo(LocalDate.parse("2026-09-01"))
    }

    @Test
    fun `returns 404 when updating an unknown csraReviewId`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = syncRole))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "csraReviewId": "${UUID.randomUUID()}", "review": ${reviewJson()} }"""))
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
        .body(BodyInserters.fromValue("""{ "review": ${reviewJson()} }"""))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 with the wrong role`() {
      webTestClient.post().uri("/nomis-sync/sync/A1234BC")
        .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
        .contentType(MediaType.APPLICATION_JSON)
        .body(BodyInserters.fromValue("""{ "review": ${reviewJson()} }"""))
        .exchange()
        .expectStatus().isForbidden
    }
  }
}
