package com.revenium.rating.ingestion

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransactionRef(val tenantId: String, val id: UUID)

/** Every query except [findDueForRating] is tenant-scoped. Do not use the inherited findById/findAll. */
interface UsageTransactionRepository : JpaRepository<UsageTransaction, UUID> {

    /**
     * Idempotent insert. Under concurrent delivery of the same event, PostgreSQL makes the second insert wait for
     * the first to commit and then skips it, so exactly one row wins without raising an exception that would
     * poison the surrounding transaction. Returns the number of inserted rows (1 or 0).
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO usage_transaction (id, tenant_id, event_id, customer_id, transaction_code, occurred_at,
                                           quantity, fingerprint, raw_payload, received_at, status, attempts,
                                           next_attempt_at)
            VALUES (:id, :tenantId, :eventId, :customerId, :transactionCode, :occurredAt,
                    :quantity, :fingerprint, :rawPayload, :receivedAt, 'PENDING', 0, :receivedAt)
            ON CONFLICT (tenant_id, event_id) DO NOTHING
        """,
    )
    fun insertIfAbsent(
        id: UUID,
        tenantId: String,
        eventId: String,
        customerId: String,
        transactionCode: String,
        occurredAt: Instant,
        quantity: BigDecimal,
        fingerprint: String,
        rawPayload: String,
        receivedAt: Instant,
    ): Int

    fun findByTenantIdAndEventId(tenantId: String, eventId: String): UsageTransaction?

    /** Claims a pending transaction for rating; returns null if it is not pending or another worker holds it. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT * FROM usage_transaction
            WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
            FOR UPDATE SKIP LOCKED
        """,
    )
    fun claimPending(tenantId: String, id: UUID): UsageTransaction?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UsageTransaction t where t.tenantId = :tenantId and t.id = :id")
    fun lockById(tenantId: String, id: UUID): UsageTransaction?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UsageTransaction t where t.tenantId = :tenantId and t.eventId = :eventId")
    fun lockByEventId(tenantId: String, eventId: String): UsageTransaction?

    /**
     * The only cross-tenant query: the background worker discovers due work for every tenant, then processes each
     * item inside that item's own tenant context. It returns identifiers only, never payload data.
     */
    @Query(
        """
        select new com.revenium.rating.ingestion.TransactionRef(t.tenantId, t.id)
        from UsageTransaction t
        where t.status = com.revenium.rating.ingestion.TransactionStatus.PENDING and t.nextAttemptAt <= :now
        order by t.nextAttemptAt
        """
    )
    fun findDueForRating(now: Instant, limit: Limit): List<TransactionRef>

    @Query(
        """
        select t from UsageTransaction t
        where t.tenantId = :tenantId and t.customerId = :customerId and t.occurredAt >= :from and t.occurredAt < :to
        order by t.occurredAt, t.eventId
        """
    )
    fun findForCustomer(tenantId: String, customerId: String, from: Instant, to: Instant): List<UsageTransaction>

    @Query(
        """
        select count(t) from UsageTransaction t
        where t.tenantId = :tenantId and t.customerId = :customerId and t.status = :status
          and t.occurredAt >= :from and t.occurredAt < :to
        """
    )
    fun countForCustomer(tenantId: String, customerId: String, status: TransactionStatus, from: Instant, to: Instant): Long
}
