package com.revenium.rating.reconciliation

import com.revenium.rating.Fixtures
import com.revenium.rating.common.InvalidRequestException
import com.revenium.rating.ingestion.DuplicateDelivery
import com.revenium.rating.ingestion.DuplicateDeliveryRepository
import com.revenium.rating.ingestion.RejectedSubmission
import com.revenium.rating.ingestion.RejectedSubmissionRepository
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransaction
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.rating.RatedChargeRepository
import com.revenium.rating.tenancy.Tenant
import com.revenium.rating.tenancy.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

private val FROM: Instant = Instant.parse("2026-08-01T00:00:00Z")
private val TO: Instant = Instant.parse("2026-09-01T00:00:00Z")

private fun duplicateOf(transaction: UsageTransaction, matches: Boolean = true) =
    DuplicateDelivery(UUID.randomUUID(), transaction.tenantId, transaction.id, transaction.eventId, matches, "{}", Fixtures.NOW)

private fun rejection() =
    RejectedSubmission(UUID.randomUUID(), Fixtures.TENANT, "bad-1", Fixtures.CUSTOMER, null, "quantity: is required", "{}", Fixtures.NOW)

class ReconciliationReportTest {

    @Test
    fun `classifies every delivery and traces amounts to events and rules`() {
        val invoiced = Fixtures.usageTransaction(eventId = "e-invoiced", status = TransactionStatus.RATED)
        val rated = Fixtures.usageTransaction(eventId = "e-rated", status = TransactionStatus.RATED)
        val pending = Fixtures.usageTransaction(eventId = "e-pending")
        val unrated = Fixtures.usageTransaction(eventId = "e-unrated", status = TransactionStatus.UNRATED)
        val failed = Fixtures.usageTransaction(eventId = "e-failed", status = TransactionStatus.FAILED)
        val invoiceId = UUID.randomUUID()
        val invoicedCharge = Fixtures.charge(invoiced, amount = "27.50", invoiceId = invoiceId)
        val lateCharge = Fixtures.charge(rated, amount = "13.75", billingPeriod = LocalDate.of(2026, 9, 1), lateArrival = true)

        val report = ReconciliationReport.assemble(
            customerId = Fixtures.CUSTOMER,
            from = FROM,
            to = TO,
            currency = "USD",
            transactions = listOf(invoiced, rated, pending, unrated, failed),
            charges = listOf(invoicedCharge, lateCharge),
            duplicates = listOf(duplicateOf(invoiced), duplicateOf(invoiced, matches = false)),
            rejections = listOf(rejection()),
        )

        assertThat(report.counts).isEqualTo(
            ReconciliationCounts(
                received = 8, accepted = 5, duplicate = 2, rejected = 1,
                pending = 1, unrated = 1, failed = 1, rated = 1, invoiced = 1,
            )
        )
        assertThat(report.invoicedAmount).isEqualTo(BigDecimal("27.50"))
        assertThat(report.ratedAmount).isEqualTo(BigDecimal("13.75"))
        assertThat(report.totalAmount).isEqualTo(BigDecimal("41.25"))
        assertThat(report.transactions.filter { it.amount != null }.sumOf { it.amount!! }).isEqualTo(report.totalAmount)

        val invoicedRow = report.transactions.single { it.eventId == "e-invoiced" }
        assertThat(invoicedRow.state).isEqualTo(ReconciliationState.INVOICED)
        assertThat(invoicedRow.priceRuleId).isEqualTo(invoicedCharge.priceRuleId)
        assertThat(invoicedRow.invoiceId).isEqualTo(invoiceId)
        assertThat(invoicedRow.duplicateDeliveries).isEqualTo(2)

        val lateRow = report.transactions.single { it.eventId == "e-rated" }
        assertThat(lateRow.state).isEqualTo(ReconciliationState.RATED)
        assertThat(lateRow.lateArrival).isTrue()
        assertThat(lateRow.billingPeriod).isEqualTo(YearMonth.of(2026, 9))

        val pendingRow = report.transactions.single { it.eventId == "e-pending" }
        assertThat(pendingRow.priceRuleId).isNull()
        assertThat(pendingRow.amount).isNull()

        assertThat(report.duplicates.map { it.fingerprintMatches }).containsExactly(true, false)
        assertThat(report.rejections.single().reasons).isEqualTo("quantity: is required")
    }

    @Test
    fun `presents stored amounts at the currency minor-unit scale`() {
        val transaction = Fixtures.usageTransaction(status = TransactionStatus.RATED)
        val report = ReconciliationReport.assemble(
            Fixtures.CUSTOMER, FROM, TO, "USD", listOf(transaction), listOf(Fixtures.charge(transaction, amount = "27.5000")), emptyList(), emptyList(),
        )

        assertThat(report.transactions.single().amount).isEqualTo(BigDecimal("27.50"))
        assertThat(report.ratedAmount).isEqualTo(BigDecimal("27.50"))
        assertThat(report.totalAmount).isEqualTo(BigDecimal("27.50"))
        assertThat(report.invoicedAmount).isEqualTo(BigDecimal("0.00"))
    }

    @Test
    fun `an empty period reconciles to zero`() {
        val report = ReconciliationReport.assemble(Fixtures.CUSTOMER, FROM, TO, "USD", emptyList(), emptyList(), emptyList(), emptyList())

        assertThat(report.counts.received).isZero()
        assertThat(report.totalAmount).isEqualByComparingTo("0")
    }
}

class ReconciliationServiceTest {

    private val transactions = mock<UsageTransactionRepository>()
    private val charges = mock<RatedChargeRepository>()
    private val duplicates = mock<DuplicateDeliveryRepository>()
    private val rejections = mock<RejectedSubmissionRepository>()
    private val tenants = mock<TenantRepository>()
    private val service = ReconciliationService(transactions, charges, duplicates, rejections, tenants)

    init {
        whenever(tenants.findById(Fixtures.TENANT)).thenReturn(Optional.of(Tenant(Fixtures.TENANT, "A", "USD")))
    }

    @Test
    fun `builds the report from tenant-scoped queries`() {
        val transaction = Fixtures.usageTransaction()
        whenever(transactions.findForCustomer(Fixtures.TENANT, Fixtures.CUSTOMER, FROM, TO)).thenReturn(listOf(transaction))
        whenever(charges.findForCustomer(Fixtures.TENANT, Fixtures.CUSTOMER, FROM, TO)).thenReturn(emptyList())
        whenever(duplicates.findByTenantIdAndTransactionIdIn(Fixtures.TENANT, listOf(transaction.id))).thenReturn(listOf(duplicateOf(transaction)))
        whenever(rejections.findForCustomer(Fixtures.TENANT, Fixtures.CUSTOMER, FROM, TO)).thenReturn(listOf(rejection()))

        val report = service.report(Fixtures.TENANT, Fixtures.CUSTOMER, FROM, TO)

        assertThat(report.counts.accepted).isEqualTo(1)
        assertThat(report.counts.duplicate).isEqualTo(1)
        assertThat(report.counts.rejected).isEqualTo(1)
        assertThat(report.currency).isEqualTo("USD")
    }

    @Test
    fun `skips the duplicate lookup when there are no transactions`() {
        val report = service.report(Fixtures.TENANT, Fixtures.CUSTOMER, FROM, TO)

        assertThat(report.counts.accepted).isZero()
        verify(duplicates, never()).findByTenantIdAndTransactionIdIn(any(), any())
    }

    @Test
    fun `rejects an inverted range`() {
        assertThatThrownBy { service.report(Fixtures.TENANT, Fixtures.CUSTOMER, TO, FROM) }.isInstanceOf(InvalidRequestException::class.java)
    }
}
