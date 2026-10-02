package com.revenium.rating.rating

import com.revenium.rating.ingestion.TransactionQueuedEvent
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.data.domain.Limit
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.util.UUID

/**
 * Drives rating outside the request thread. Not tenant-scoped itself: it discovers due work across tenants and
 * binds each item's own tenant (read from the claimed row) before calling the tenant-scoped [RatingService].
 *
 * Safe with any number of instances: rows are claimed with FOR UPDATE SKIP LOCKED, so each row is rated by at most
 * one worker at a time, and a row that a crashed instance was rating becomes claimable again once its database
 * connection is gone.
 */
@Component
class RatingWorker(
    private val usageTransactionRepository: UsageTransactionRepository,
    private val ratingService: RatingService,
    private val ratingProperties: RatingProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Processes one batch of due transactions and returns how many were attempted. */
    fun processDueBatch(): Int {
        val due = usageTransactionRepository.findDueForRating(clock.instant(), Limit.of(ratingProperties.batchSize))
        due.forEach { process(it.tenantId, it.id) }
        return due.size
    }

    fun process(tenantId: String, transactionId: UUID) {
        TenantContext.runAs(tenantId) {
            try {
                ratingService.rate(tenantId, transactionId)
            } catch (ex: Exception) {
                log.warn("Rating failed for transaction {} of tenant {}: {}", transactionId, tenantId, ex.toString())
                ratingService.recordFailure(tenantId, transactionId, ex.toString())
            }
        }
    }
}

/** The safety net: picks up anything not handled on commit (lost dispatch, retries after backoff, restarts). */
@Component
@ConditionalOnBooleanProperty("app.rating.scheduler-enabled")
class RatingScheduler(
    private val ratingWorker: RatingWorker,
    private val ratingProperties: RatingProperties,
) {

    @Scheduled(fixedDelayString = "\${app.rating.poll-interval}")
    fun drain() {
        while (ratingWorker.processDueBatch() == ratingProperties.batchSize) {
            // keep draining while full batches come back
        }
    }
}

/**
 * The fast path: rates a transaction right after the transaction that queued it commits. A rolled-back
 * ingestion never reaches this listener. If the executor rejects or loses the task, the scheduler still
 * finds the PENDING row.
 */
@Component
@ConditionalOnBooleanProperty("app.rating.dispatch-on-commit")
class RatingDispatcher(private val ratingWorker: RatingWorker) {

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onTransactionQueued(event: TransactionQueuedEvent) {
        ratingWorker.process(event.tenantId, event.transactionId)
    }
}
