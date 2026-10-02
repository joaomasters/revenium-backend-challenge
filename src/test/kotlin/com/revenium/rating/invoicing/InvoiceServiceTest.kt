package com.revenium.rating.invoicing

import com.revenium.rating.Fixtures
import com.revenium.rating.common.ConflictException
import com.revenium.rating.common.InvalidRequestException
import com.revenium.rating.common.NotFoundException
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.rating.RatedChargeRepository
import com.revenium.rating.tenancy.Tenant
import com.revenium.rating.tenancy.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class InvoiceServiceTest {

    private val invoices = mock<InvoiceRepository>()
    private val charges = mock<RatedChargeRepository>()
    private val tenants = mock<TenantRepository>()
    private val periodGuard = mock<BillingPeriodGuard>()
    private val transactions = mock<UsageTransactionRepository>()
    private val august = YearMonth.of(2026, 8)
    private val augustStart = LocalDate.of(2026, 8, 1)

    private fun service(clock: Clock = Fixtures.CLOCK) = InvoiceService(invoices, charges, transactions, tenants, periodGuard, clock)

    @BeforeEach
    fun setUp() {
        whenever(tenants.findById(Fixtures.TENANT)).thenReturn(Optional.of(Tenant(Fixtures.TENANT, "A", "USD")))
        whenever(invoices.persist(any())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `issues an invoice from the period's uninvoiced charges and links every charge to it`() {
        val periodCharges = listOf(
            Fixtures.charge(Fixtures.usageTransaction(transactionCode = "VEHICLE_REGISTRATION"), amount = "27.50"),
            Fixtures.charge(Fixtures.usageTransaction(transactionCode = "TITLE_TRANSFER", quantity = BigDecimal.ONE), amount = "7.25"),
        )
        whenever(charges.findUninvoiced(Fixtures.TENANT, Fixtures.CUSTOMER, augustStart)).thenReturn(periodCharges)

        val issued = service().issue(Fixtures.TENANT, Fixtures.CUSTOMER, august)

        val invoice = issued.details.invoice
        assertThat(issued.created).isTrue()
        assertThat(invoice.billingPeriod).isEqualTo(augustStart)
        assertThat(invoice.currency).isEqualTo("USD")
        assertThat(invoice.transactionCount).isEqualTo(2)
        assertThat(invoice.totalAmount).isEqualTo(BigDecimal("34.75"))
        assertThat(invoice.lines.map { it.transactionCode to it.amount })
            .containsExactly("TITLE_TRANSFER" to BigDecimal("7.25"), "VEHICLE_REGISTRATION" to BigDecimal("27.50"))
        assertThat(periodCharges).allSatisfy { assertThat(it.invoiceId).isEqualTo(invoice.id) }
        inOrder(periodGuard, charges) {
            verify(periodGuard).lock(Fixtures.TENANT, Fixtures.CUSTOMER, august)
            verify(charges).findUninvoiced(Fixtures.TENANT, Fixtures.CUSTOMER, augustStart)
        }
    }

    @Test
    fun `refuses to close a period while usage that occurred in it is still pending rating`() {
        whenever(
            transactions.countForCustomer(
                Fixtures.TENANT, Fixtures.CUSTOMER, TransactionStatus.PENDING,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
            )
        ).thenReturn(2)

        assertThatThrownBy { service().issue(Fixtures.TENANT, Fixtures.CUSTOMER, august) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("2 transaction(s)")
            .hasMessageContaining("pending")
        verify(invoices, never()).persist(any())
        inOrder(periodGuard, transactions) {
            verify(periodGuard).lock(Fixtures.TENANT, Fixtures.CUSTOMER, august)
            verify(transactions).countForCustomer(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `pending usage does not prevent returning an invoice that was already issued`() {
        val existing = invoice()
        whenever(invoices.findByTenantIdAndCustomerIdAndBillingPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, augustStart)).thenReturn(existing)
        whenever(transactions.countForCustomer(any(), any(), any(), any(), any())).thenReturn(5)

        assertThat(service().issue(Fixtures.TENANT, Fixtures.CUSTOMER, august).created).isFalse()
    }

    @Test
    fun `issuing an empty period still closes it with a zero invoice`() {
        val issued = service().issue(Fixtures.TENANT, Fixtures.CUSTOMER, august)

        assertThat(issued.details.invoice.totalAmount).isEqualByComparingTo("0")
        assertThat(issued.details.invoice.lines).isEmpty()
    }

    @Test
    fun `returns the existing invoice when the period was already issued`() {
        val existing = invoice()
        val linked = listOf(Fixtures.charge(invoiceId = existing.id))
        whenever(invoices.findByTenantIdAndCustomerIdAndBillingPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, augustStart)).thenReturn(existing)
        whenever(charges.findByInvoice(Fixtures.TENANT, existing.id)).thenReturn(linked)

        val issued = service().issue(Fixtures.TENANT, Fixtures.CUSTOMER, august)

        assertThat(issued.created).isFalse()
        assertThat(issued.details).isEqualTo(InvoiceDetails(existing, linked))
        verify(invoices, never()).persist(any())
        verify(charges, never()).findUninvoiced(any(), any(), any())
    }

    @Test
    fun `refuses to close a period that has not ended`() {
        val lastInstantOfAugust = Clock.fixed(Instant.parse("2026-08-31T23:59:59Z"), ZoneOffset.UTC)

        assertThatThrownBy { service(lastInstantOfAugust).issue(Fixtures.TENANT, Fixtures.CUSTOMER, august) }
            .isInstanceOf(ConflictException::class.java)
        verify(periodGuard, never()).lock(any(), any(), any())
    }

    @Test
    fun `a period can be closed at the first instant after it ends`() {
        val firstInstantOfSeptember = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)

        assertThat(service(firstInstantOfSeptember).issue(Fixtures.TENANT, Fixtures.CUSTOMER, august).created).isTrue()
    }

    @Test
    fun `gets an invoice with its charges`() {
        val existing = invoice()
        whenever(invoices.findByTenantIdAndId(Fixtures.TENANT, existing.id)).thenReturn(existing)
        whenever(charges.findByInvoice(Fixtures.TENANT, existing.id)).thenReturn(emptyList())

        assertThat(service().get(Fixtures.TENANT, existing.id).invoice).isSameAs(existing)
    }

    @Test
    fun `an invoice of another tenant is not found`() {
        assertThatThrownBy { service().get(Fixtures.TENANT, UUID.randomUUID()) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `lists a customer's invoices`() {
        val existing = invoice()
        whenever(invoices.findByTenantIdAndCustomerIdOrderByBillingPeriod(Fixtures.TENANT, Fixtures.CUSTOMER)).thenReturn(listOf(existing))

        assertThat(service().list(Fixtures.TENANT, Fixtures.CUSTOMER)).containsExactly(existing)
    }

    private fun invoice() = Invoice(
        UUID.randomUUID(), Fixtures.TENANT, Fixtures.CUSTOMER, augustStart, "USD", 1, BigDecimal("27.50"), Fixtures.NOW, emptyList()
    )
}

class InvoiceSummaryServiceTest {

    private val charges = mock<RatedChargeRepository>()
    private val transactions = mock<UsageTransactionRepository>()
    private val tenants = mock<TenantRepository>()
    private val service = InvoiceSummaryService(charges, transactions, tenants)
    private val from = Instant.parse("2026-08-01T00:00:00Z")
    private val to = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `summarizes rated usage by transaction code and counts what is not rated yet`() {
        val rated = Fixtures.usageTransaction(status = TransactionStatus.RATED)
        val unrated = Fixtures.usageTransaction(status = TransactionStatus.UNRATED)
        whenever(tenants.findById(Fixtures.TENANT)).thenReturn(Optional.of(Tenant(Fixtures.TENANT, "A", "USD")))
        // Amounts come back from NUMERIC(19,4) with scale 4; the summary presents them at the currency's scale.
        whenever(charges.findForCustomer(Fixtures.TENANT, Fixtures.CUSTOMER, from, to)).thenReturn(listOf(Fixtures.charge(rated, amount = "27.5000")))
        whenever(transactions.findForCustomer(Fixtures.TENANT, Fixtures.CUSTOMER, from, to)).thenReturn(listOf(rated, unrated))

        val summary = service.summarize(Fixtures.TENANT, Fixtures.CUSTOMER, from, to)

        assertThat(summary.lines).containsExactly(TransactionCodeTotal("VEHICLE_REGISTRATION", 1, BigDecimal("2"), BigDecimal("27.50")))
        assertThat(summary.transactionCount).isEqualTo(1)
        assertThat(summary.totalAmount).isEqualTo(BigDecimal("27.50"))
        assertThat(summary.unratedTransactionCount).isEqualTo(1)
        assertThat(summary.currency).isEqualTo("USD")
    }

    @Test
    fun `rejects an empty or inverted range`() {
        assertThatThrownBy { service.summarize(Fixtures.TENANT, Fixtures.CUSTOMER, to, to) }.isInstanceOf(InvalidRequestException::class.java)
    }
}
