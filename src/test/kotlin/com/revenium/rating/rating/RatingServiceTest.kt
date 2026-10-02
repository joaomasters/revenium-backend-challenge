package com.revenium.rating.rating

import com.revenium.rating.Fixtures
import com.revenium.rating.common.NotFoundException
import com.revenium.rating.ingestion.ProcessingEventType
import com.revenium.rating.ingestion.ProcessingLog
import com.revenium.rating.ingestion.TransactionQueuedEvent
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.invoicing.BillingPeriodGuard
import com.revenium.rating.pricing.PriceRuleRepository
import com.revenium.rating.tenancy.Tenant
import com.revenium.rating.tenancy.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

class RatingServiceTest {

    private val transactions = mock<UsageTransactionRepository>()
    private val priceRules = mock<PriceRuleRepository>()
    private val charges = mock<RatedChargeRepository>()
    private val tenants = mock<TenantRepository>()
    private val periodGuard = mock<BillingPeriodGuard>()
    private val processingLog = mock<ProcessingLog>()
    private val events = mock<ApplicationEventPublisher>()
    private val properties = RatingProperties(maxAttempts = 2, retryBackoff = Duration.ofSeconds(10))
    private val service = RatingService(
        transactions, priceRules, charges, tenants, periodGuard, processingLog, events, properties, Fixtures.CLOCK
    )

    private val julyRule = Fixtures.priceRule(unitPrice = "12.50", from = "2026-01-01T00:00:00Z", to = "2026-08-01T00:00:00Z")
    private val augustRule = Fixtures.priceRule(unitPrice = "13.75", from = "2026-08-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        whenever(tenants.findById(Fixtures.TENANT)).thenReturn(Optional.of(Tenant(Fixtures.TENANT, "A", "USD")))
        whenever(priceRules.findByTenantIdAndTransactionCodeOrderByEffectiveFrom(Fixtures.TENANT, "VEHICLE_REGISTRATION"))
            .thenReturn(listOf(julyRule, augustRule))
        whenever(charges.persistAndFlush(any())).thenAnswer { it.arguments[0] }
    }

    private fun claim(occurredAt: String, quantity: String = "2") =
        Fixtures.usageTransaction(occurredAt = Instant.parse(occurredAt), quantity = BigDecimal(quantity)).also {
            whenever(transactions.claimPending(Fixtures.TENANT, it.id)).thenReturn(it)
        }

    @Test
    fun `rates with the rule effective when the usage occurred and records the rule identity`() {
        val transaction = claim("2026-08-15T14:22:31Z")
        whenever(periodGuard.lockFirstOpenPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, YearMonth.of(2026, 8))).thenReturn(YearMonth.of(2026, 8))

        val charge = (service.rate(Fixtures.TENANT, transaction.id) as RatingOutcome.Rated).charge

        assertThat(charge.priceRuleId).isEqualTo(augustRule.id)
        assertThat(charge.unitPrice).isEqualByComparingTo("13.75")
        assertThat(charge.amount).isEqualTo(BigDecimal("27.50"))
        assertThat(charge.currency).isEqualTo("USD")
        assertThat(charge.billingPeriod).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(charge.lateArrival).isFalse()
        assertThat(transaction.status).isEqualTo(TransactionStatus.RATED)
        verify(processingLog).record(eq(Fixtures.TENANT), eq(transaction.id), eq(ProcessingEventType.RATED), argThat { contains(augustRule.id.toString()) })
    }

    @Test
    fun `uses the older rule for usage just before the price change even if it arrives later`() {
        val transaction = claim("2026-07-31T23:59:59.999999Z")
        whenever(periodGuard.lockFirstOpenPeriod(any(), any(), any())).thenReturn(YearMonth.of(2026, 7))

        val charge = (service.rate(Fixtures.TENANT, transaction.id) as RatingOutcome.Rated).charge

        assertThat(charge.priceRuleId).isEqualTo(julyRule.id)
        assertThat(charge.amount).isEqualTo(BigDecimal("25.00"))
    }

    @Test
    fun `marks a late arrival when its own period is already invoiced`() {
        val transaction = claim("2026-08-15T14:22:31Z")
        whenever(periodGuard.lockFirstOpenPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, YearMonth.of(2026, 8))).thenReturn(YearMonth.of(2026, 9))

        val charge = (service.rate(Fixtures.TENANT, transaction.id) as RatingOutcome.Rated).charge

        assertThat(charge.billingPeriod).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(charge.lateArrival).isTrue()
        assertThat(charge.priceRuleId).isEqualTo(augustRule.id)
    }

    @Test
    fun `leaves a transaction unrated when no rule covers its occurrence`() {
        val transaction = claim("2025-12-31T23:59:59Z")

        val outcome = service.rate(Fixtures.TENANT, transaction.id)

        assertThat(outcome).isInstanceOf(RatingOutcome.Unrated::class.java)
        assertThat(transaction.status).isEqualTo(TransactionStatus.UNRATED)
        assertThat(transaction.statusReason).contains("VEHICLE_REGISTRATION")
        verify(charges, never()).persistAndFlush(any())
        verify(periodGuard, never()).lockFirstOpenPeriod(any(), any(), any())
        verify(processingLog).record(eq(Fixtures.TENANT), eq(transaction.id), eq(ProcessingEventType.UNRATED), anyOrNull())
    }

    @Test
    fun `does nothing when the transaction cannot be claimed`() {
        val id = UUID.randomUUID()
        whenever(transactions.claimPending(Fixtures.TENANT, id)).thenReturn(null)

        assertThat(service.rate(Fixtures.TENANT, id)).isEqualTo(RatingOutcome.NotClaimed)
        verify(charges, never()).persistAndFlush(any())
    }

    @Test
    fun `records a failed attempt and schedules a retry`() {
        val transaction = Fixtures.usageTransaction()
        whenever(transactions.lockById(Fixtures.TENANT, transaction.id)).thenReturn(transaction)

        service.recordFailure(Fixtures.TENANT, transaction.id, "db timeout")

        assertThat(transaction.status).isEqualTo(TransactionStatus.PENDING)
        assertThat(transaction.attempts).isEqualTo(1)
        assertThat(transaction.nextAttemptAt).isEqualTo(Fixtures.NOW.plusSeconds(10))
        verify(processingLog).record(Fixtures.TENANT, transaction.id, ProcessingEventType.ATTEMPT_FAILED, "db timeout")
    }

    @Test
    fun `parks a poison event as failed once the attempt budget is exhausted`() {
        val transaction = Fixtures.usageTransaction()
        whenever(transactions.lockById(Fixtures.TENANT, transaction.id)).thenReturn(transaction)

        service.recordFailure(Fixtures.TENANT, transaction.id, "boom")
        service.recordFailure(Fixtures.TENANT, transaction.id, "boom")

        assertThat(transaction.status).isEqualTo(TransactionStatus.FAILED)
        verify(processingLog).record(Fixtures.TENANT, transaction.id, ProcessingEventType.FAILED, "boom")
    }

    @Test
    fun `ignores a failure report for a transaction that was completed meanwhile`() {
        val transaction = Fixtures.usageTransaction(status = TransactionStatus.RATED)
        whenever(transactions.lockById(Fixtures.TENANT, transaction.id)).thenReturn(transaction)

        service.recordFailure(Fixtures.TENANT, transaction.id, "late failure")

        assertThat(transaction.attempts).isZero()
        verify(processingLog, never()).record(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `ignores a failure report for an unknown transaction`() {
        service.recordFailure(Fixtures.TENANT, UUID.randomUUID(), "gone")

        verify(processingLog, never()).record(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `requeues an unrated transaction, keeps the history and dispatches it`() {
        val transaction = Fixtures.usageTransaction(eventId = "evt-9", status = TransactionStatus.UNRATED)
        whenever(transactions.lockByEventId(Fixtures.TENANT, "evt-9")).thenReturn(transaction)

        service.requeue(Fixtures.TENANT, "evt-9")

        assertThat(transaction.status).isEqualTo(TransactionStatus.PENDING)
        verify(processingLog).record(Fixtures.TENANT, transaction.id, ProcessingEventType.REQUEUED, "previous status UNRATED")
        verify(events).publishEvent(TransactionQueuedEvent(Fixtures.TENANT, transaction.id))
    }

    @Test
    fun `requeue of an unknown event is not found`() {
        assertThatThrownBy { service.requeue(Fixtures.TENANT, "missing") }.isInstanceOf(NotFoundException::class.java)
    }
}
