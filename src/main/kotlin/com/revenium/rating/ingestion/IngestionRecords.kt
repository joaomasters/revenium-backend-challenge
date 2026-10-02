package com.revenium.rating.ingestion

import com.revenium.rating.common.InsertOnlyRepository
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/** Evidence of a re-delivered eventId. The original transaction is never modified by a re-delivery. */
@Entity
@Table(name = "duplicate_delivery")
class DuplicateDelivery(
    @Id
    val id: UUID,
    val tenantId: String,
    val transactionId: UUID,
    val eventId: String,
    val fingerprintMatches: Boolean,
    val rawPayload: String,
    val receivedAt: Instant,
)

interface DuplicateDeliveryRepository : JpaRepository<DuplicateDelivery, UUID>, InsertOnlyRepository<DuplicateDelivery> {

    fun findByTenantIdAndTransactionIdIn(tenantId: String, transactionIds: Collection<UUID>): List<DuplicateDelivery>
}

/** A submission that failed validation, kept with its raw payload so rejections are auditable. */
@Entity
@Table(name = "rejected_submission")
class RejectedSubmission(
    @Id
    val id: UUID,
    val tenantId: String,
    val eventId: String?,
    val customerId: String?,
    val occurredAt: Instant?,
    val reasons: String,
    val rawPayload: String,
    val receivedAt: Instant,
)

interface RejectedSubmissionRepository : JpaRepository<RejectedSubmission, UUID>, InsertOnlyRepository<RejectedSubmission> {

    /** Rejections without a parseable occurredAt are attributed to the period in which they were received. */
    @Query(
        """
        select r from RejectedSubmission r
        where r.tenantId = :tenantId and r.customerId = :customerId
          and coalesce(r.occurredAt, r.receivedAt) >= :from and coalesce(r.occurredAt, r.receivedAt) < :to
        order by r.receivedAt
        """
    )
    fun findForCustomer(tenantId: String, customerId: String, from: Instant, to: Instant): List<RejectedSubmission>
}

enum class ProcessingEventType { ACCEPTED, RATED, UNRATED, ATTEMPT_FAILED, FAILED, REQUEUED }

/** Append-only processing history of a transaction. */
@Entity
@Table(name = "transaction_processing_log")
class ProcessingLogEntry(
    @Id
    val id: UUID,
    val tenantId: String,
    val transactionId: UUID,
    @Enumerated(EnumType.STRING)
    val eventType: ProcessingEventType,
    val detail: String?,
    val recordedAt: Instant,
)

interface ProcessingLogRepository : JpaRepository<ProcessingLogEntry, UUID>, InsertOnlyRepository<ProcessingLogEntry>
