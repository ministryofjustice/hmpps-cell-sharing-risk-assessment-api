package uk.gov.justice.digital.hmpps.cellsharingriskassessmentapi.service

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Defers [block] until the surrounding transaction commits, so a write that is later rolled back never
 * announces itself. Runs immediately when there is no transaction in progress.
 */
internal fun afterCommit(block: () -> Unit) {
  if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    block()
    return
  }
  TransactionSynchronizationManager.registerSynchronization(
    object : TransactionSynchronization {
      override fun afterCommit() = block()
    },
  )
}
