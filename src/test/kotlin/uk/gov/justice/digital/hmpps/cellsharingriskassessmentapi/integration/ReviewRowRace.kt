package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.integration

import jakarta.persistence.EntityManager
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Stages the interleaving that used to lose a write to `csra_review` (MAPA-437).
 *
 * [commitOnceBlocked] runs `first` in a transaction and holds it open, with its changes flushed, while
 * `startCompetingWrite` sets off a second write to the same review - an HTTP call on another thread, or an
 * SQS message for the listener. That second write reads the last committed copy of the review, so it cannot
 * see `first`'s change, and then blocks on the row when it tries to save. Only once it is blocked does
 * `first` commit, so the second write always resumes against a row whose version has moved on.
 *
 * Detected through `pg_blocking_pids` rather than a sleep, so the test neither races nor waits longer than
 * it has to.
 */
class ReviewRowRace(
  private val transactionManager: PlatformTransactionManager,
  private val jdbcTemplate: JdbcTemplate,
  private val entityManager: EntityManager,
) {
  fun <R : Any> commitOnceBlocked(first: () -> Unit, startCompetingWrite: () -> R): R = TransactionTemplate(transactionManager).execute {
    first()
    entityManager.flush()
    val holder = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
    startCompetingWrite().also {
      await untilCallTo { blockedBehind(holder) } matches { it == 1 }
    }
  }!!

  private fun blockedBehind(holder: Int) = jdbcTemplate.queryForObject(
    "SELECT COUNT(*) FROM pg_stat_activity " +
      "WHERE datname = current_database() AND wait_event_type = 'Lock' " +
      "AND query LIKE '%csra_review%' AND ? = ANY(pg_blocking_pids(pid))",
    Int::class.java,
    holder,
  )
}
