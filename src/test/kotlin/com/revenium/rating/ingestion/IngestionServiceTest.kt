package com.revenium.rating.ingestion

import com.revenium.rating.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant

class IngestionServiceTest {

    private val validator = mock<TransactionValidator>()
    private val transactions = mock<UsageTransactionRepository>()
    private val duplicates = mock<DuplicateDeliveryRepository>()
    private val rejections = mock<RejectedSubmissionRepository>()
    private val processingLog = mock<ProcessingLog>()
    private val events = mock<ApplicationEventPublisher>()
    private val service = IngestionService(validator, transactions, duplicates, rejections, processingLog, events, Fixtures.CLOCK)

    private val valid = ValidTransaction("evt-1", Fixtures.CUSTOMER, "VEHICLE_REGISTRATION", Instant.parse("2026-08-15T14:22:31Z"), BigDecimal("2"))
    private val raw = """{"eventId":"evt-1"}"""

    private fun givenInsertReturns(rows: Int) {
        whenever(validator.validate(raw, Fixtures.TENANT)).thenReturn(ValidationResult.Valid(valid))
        whenever(transactions.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rows)
    }

    @Test
    fun `accepts a new event, logs it and queues it for rating after commit`() {
        givenInsertReturns(1)

        val result = service.ingest(Fixtures.TENANT, raw) as IngestionResult.Accepted

        verify(transactions).insertIfAbsent(
            eq(result.transactionId), eq(Fixtures.TENANT), eq("evt-1"), eq(Fixtures.CUSTOMER), eq("VEHICLE_REGISTRATION"),
            eq(valid.occurredAt), eq(valid.quantity), eq(valid.fingerprint), eq(raw), eq(Fixtures.NOW),
        )
        verify(processingLog).record(Fixtures.TENANT, result.transactionId, ProcessingEventType.ACCEPTED)
        verify(events).publishEvent(TransactionQueuedEvent(Fixtures.TENANT, result.transactionId))
    }

    @Test
    fun `reports a re-delivery with identical content as a duplicate without queueing anything`() {
        givenInsertReturns(0)
        val original = Fixtures.usageTransaction(eventId = "evt-1")
        whenever(transactions.findByTenantIdAndEventId(Fixtures.TENANT, "evt-1"))
            .thenReturn(withFingerprint(original, valid.fingerprint))

        val result = service.ingest(Fixtures.TENANT, raw)

        assertThat(result).isEqualTo(IngestionResult.Duplicate(original.id, "evt-1", TransactionStatus.PENDING))
        val recorded = argumentCaptor<DuplicateDelivery>().apply { verify(duplicates).persist(capture()) }.firstValue
        assertThat(recorded.transactionId).isEqualTo(original.id)
        assertThat(recorded.fingerprintMatches).isTrue()
        assertThat(recorded.rawPayload).isEqualTo(raw)
        verify(events, never()).publishEvent(any<Any>())
        verify(processingLog, never()).record(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `reports a re-used eventId with different content as a conflict and keeps the original`() {
        givenInsertReturns(0)
        val original = Fixtures.usageTransaction(eventId = "evt-1")
        whenever(transactions.findByTenantIdAndEventId(Fixtures.TENANT, "evt-1")).thenReturn(original)

        val result = service.ingest(Fixtures.TENANT, raw)

        assertThat(result).isEqualTo(IngestionResult.DuplicateConflict(original.id, "evt-1"))
        val recorded = argumentCaptor<DuplicateDelivery>().apply { verify(duplicates).persist(capture()) }.firstValue
        assertThat(recorded.fingerprintMatches).isFalse()
        verify(events, never()).publishEvent(any<Any>())
    }

    @Test
    fun `records rejected submissions with their raw payload and reasons`() {
        val invalid = ValidationResult.Invalid(listOf("quantity: is required", "occurredAt: is required"), eventId = "evt-1", customerId = "c-1")
        whenever(validator.validate(raw, Fixtures.TENANT)).thenReturn(invalid)
        whenever(rejections.persist(any())).thenAnswer { it.arguments[0] }

        val result = service.ingest(Fixtures.TENANT, raw) as IngestionResult.Rejected

        val recorded = argumentCaptor<RejectedSubmission>().apply { verify(rejections).persist(capture()) }.firstValue
        assertThat(result.rejectionId).isEqualTo(recorded.id)
        assertThat(result.errors).isEqualTo(invalid.errors)
        assertThat(recorded.reasons).isEqualTo("quantity: is required; occurredAt: is required")
        assertThat(recorded.rawPayload).isEqualTo(raw)
        assertThat(recorded.eventId).isEqualTo("evt-1")
        assertThat(recorded.customerId).isEqualTo("c-1")
        verify(transactions, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        verify(events, never()).publishEvent(any<Any>())
    }

    @Test
    fun `propagates the tenant mismatch flag`() {
        whenever(validator.validate(raw, Fixtures.TENANT))
            .thenReturn(ValidationResult.Invalid(listOf("tenantId: does not match the authenticated tenant"), tenantMismatch = true))
        whenever(rejections.persist(any())).thenAnswer { it.arguments[0] }

        val result = service.ingest(Fixtures.TENANT, raw) as IngestionResult.Rejected

        assertThat(result.tenantMismatch).isTrue()
    }

    private fun withFingerprint(transaction: UsageTransaction, fingerprint: String) = UsageTransaction(
        id = transaction.id,
        tenantId = transaction.tenantId,
        eventId = transaction.eventId,
        customerId = transaction.customerId,
        transactionCode = transaction.transactionCode,
        occurredAt = transaction.occurredAt,
        quantity = transaction.quantity,
        fingerprint = fingerprint,
        rawPayload = transaction.rawPayload,
        receivedAt = transaction.receivedAt,
    )
}
