package com.revenium.rating.ingestion

import com.revenium.rating.common.ConflictException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TransactionStatus { PENDING, RATED, UNRATED, FAILED }

/**
 * An accepted usage transaction. The input columns (everything up to receivedAt) are immutable evidence;
 * only the processing state changes over time, and every change is also appended to the processing log.
 */
@Entity
@Table(name = "usage_transaction")
class UsageTransaction(
    @Id
    val id: UUID,
    @Column(updatable = false) val tenantId: String,
    @Column(updatable = false) val eventId: String,
    @Column(updatable = false) val customerId: String,
    @Column(updatable = false) val transactionCode: String,
    @Column(updatable = false) val occurredAt: Instant,
    @Column(updatable = false) val quantity: BigDecimal,
    @Column(updatable = false) val fingerprint: String,
    @Column(updatable = false) val rawPayload: String,
    @Column(updatable = false) val receivedAt: Instant,
    status: TransactionStatus = TransactionStatus.PENDING,
    attempts: Int = 0,
    nextAttemptAt: Instant = receivedAt,
) {
    @Enumerated(EnumType.STRING)
    var status: TransactionStatus = status
        protected set

    var attempts: Int = attempts
        protected set

    var nextAttemptAt: Instant = nextAttemptAt
        protected set

    var statusReason: String? = null
        protected set

    var processedAt: Instant? = null
        protected set

    fun markRated(at: Instant) {
        requirePending()
        status = TransactionStatus.RATED
        statusReason = null
        processedAt = at
    }

    fun markUnrated(reason: String, at: Instant) {
        requirePending()
        status = TransactionStatus.UNRATED
        statusReason = reason
        processedAt = at
    }

    /** Returns true when the attempt budget is exhausted and the transaction is parked as FAILED (poison event). */
    fun recordFailedAttempt(error: String, at: Instant, maxAttempts: Int, retryBackoff: Duration): Boolean {
        requirePending()
        attempts += 1
        statusReason = error
        if (attempts >= maxAttempts) {
            status = TransactionStatus.FAILED
            processedAt = at
            return true
        }
        nextAttemptAt = at.plus(retryBackoff.multipliedBy(attempts.toLong()))
        return false
    }

    fun requeue(at: Instant) {
        if (status != TransactionStatus.UNRATED && status != TransactionStatus.FAILED) {
            throw ConflictException("Only UNRATED or FAILED transactions can be reprocessed; event $eventId is $status")
        }
        status = TransactionStatus.PENDING
        attempts = 0
        nextAttemptAt = at
        statusReason = null
        processedAt = null
    }

    private fun requirePending() {
        check(status == TransactionStatus.PENDING) { "Transaction $id is $status, expected PENDING" }
    }
}
