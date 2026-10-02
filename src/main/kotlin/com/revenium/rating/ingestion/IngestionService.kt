package com.revenium.rating.ingestion

import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Published inside the transaction that makes a transaction PENDING; consumed only after that transaction commits. */
data class TransactionQueuedEvent(val tenantId: String, val transactionId: UUID)

sealed interface IngestionResult {
    data class Accepted(val transactionId: UUID, val eventId: String) : IngestionResult

    data class Duplicate(val transactionId: UUID, val eventId: String, val processingStatus: TransactionStatus) : IngestionResult

    /** Same eventId re-used with different billable content. The original is kept; the new content is not billed. */
    data class DuplicateConflict(val transactionId: UUID, val eventId: String) : IngestionResult

    data class Rejected(val rejectionId: UUID, val errors: List<String>, val tenantMismatch: Boolean) : IngestionResult
}

@Service
@TenantScoped
class IngestionService(
    private val validator: TransactionValidator,
    private val usageTransactionRepository: UsageTransactionRepository,
    private val duplicateDeliveryRepository: DuplicateDeliveryRepository,
    private val rejectedSubmissionRepository: RejectedSubmissionRepository,
    private val processingLog: ProcessingLog,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {

    /**
     * One transaction per delivery: the accepted row, its log entry and the queued event commit atomically.
     * Rating cannot start before commit, because workers only see committed PENDING rows and the after-commit
     * listener fires only on a successful commit.
     */
    @Transactional
    fun ingest(@TenantId tenantId: String, rawPayload: String): IngestionResult =
        when (val validation = validator.validate(rawPayload, tenantId)) {
            is ValidationResult.Invalid -> reject(tenantId, rawPayload, validation)
            is ValidationResult.Valid -> accept(tenantId, rawPayload, validation.transaction)
        }

    private fun accept(tenantId: String, rawPayload: String, transaction: ValidTransaction): IngestionResult {
        val receivedAt = clock.instant()
        val newId = UUID.randomUUID()
        val inserted = usageTransactionRepository.insertIfAbsent(
            id = newId,
            tenantId = tenantId,
            eventId = transaction.eventId,
            customerId = transaction.customerId,
            transactionCode = transaction.transactionCode,
            occurredAt = transaction.occurredAt,
            quantity = transaction.quantity,
            fingerprint = transaction.fingerprint,
            rawPayload = rawPayload,
            receivedAt = receivedAt,
        )
        if (inserted == 1) {
            processingLog.record(tenantId, newId, ProcessingEventType.ACCEPTED)
            eventPublisher.publishEvent(TransactionQueuedEvent(tenantId, newId))
            return IngestionResult.Accepted(newId, transaction.eventId)
        }

        val original = checkNotNull(usageTransactionRepository.findByTenantIdAndEventId(tenantId, transaction.eventId)) {
            "Insert for event ${transaction.eventId} conflicted but no row is visible"
        }
        val fingerprintMatches = original.fingerprint == transaction.fingerprint
        duplicateDeliveryRepository.persist(
            DuplicateDelivery(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                transactionId = original.id,
                eventId = transaction.eventId,
                fingerprintMatches = fingerprintMatches,
                rawPayload = rawPayload,
                receivedAt = receivedAt,
            )
        )
        return if (fingerprintMatches) {
            IngestionResult.Duplicate(original.id, original.eventId, original.status)
        } else {
            IngestionResult.DuplicateConflict(original.id, original.eventId)
        }
    }

    private fun reject(tenantId: String, rawPayload: String, invalid: ValidationResult.Invalid): IngestionResult.Rejected {
        val rejection = rejectedSubmissionRepository.persist(
            RejectedSubmission(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                eventId = invalid.eventId,
                customerId = invalid.customerId,
                occurredAt = invalid.occurredAt,
                reasons = invalid.errors.joinToString("; "),
                rawPayload = rawPayload,
                receivedAt = clock.instant(),
            )
        )
        return IngestionResult.Rejected(rejection.id, invalid.errors, invalid.tenantMismatch)
    }
}
