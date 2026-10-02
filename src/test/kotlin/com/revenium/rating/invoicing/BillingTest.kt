package com.revenium.rating.invoicing

import com.revenium.rating.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class BillingPeriodsTest {

    @Test
    fun `assigns instants to UTC calendar months`() {
        assertThat(BillingPeriods.containing(Instant.parse("2026-08-31T23:59:59.999999Z"))).isEqualTo(YearMonth.of(2026, 8))
        assertThat(BillingPeriods.containing(Instant.parse("2026-09-01T00:00:00Z"))).isEqualTo(YearMonth.of(2026, 9))
    }

    @Test
    fun `period end is the exclusive start of the next month`() {
        assertThat(BillingPeriods.start(YearMonth.of(2026, 12))).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"))
        assertThat(BillingPeriods.end(YearMonth.of(2026, 12))).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"))
    }
}

class BillingPeriodGuardTest {

    private val jdbcTemplate = mock<JdbcTemplate>()
    private val invoices = mock<InvoiceRepository>()
    private val guard = BillingPeriodGuard(jdbcTemplate, invoices)

    @Test
    fun `returns the usage period when it has no invoice`() {
        assertThat(guard.lockFirstOpenPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, YearMonth.of(2026, 8))).isEqualTo(YearMonth.of(2026, 8))
    }

    @Test
    fun `moves a late arrival to the first later period without an invoice, locking each period in order`() {
        whenever(invoices.existsByTenantIdAndCustomerIdAndBillingPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, LocalDate.of(2026, 8, 1))).thenReturn(true)
        whenever(invoices.existsByTenantIdAndCustomerIdAndBillingPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, LocalDate.of(2026, 9, 1))).thenReturn(true)

        val period = guard.lockFirstOpenPeriod(Fixtures.TENANT, Fixtures.CUSTOMER, YearMonth.of(2026, 8))

        assertThat(period).isEqualTo(YearMonth.of(2026, 10))
        inOrder(jdbcTemplate) {
            verify(jdbcTemplate).query(any<String>(), any<RowCallbackHandler>(), eq("tenant-a|customer-42|2026-08"))
            verify(jdbcTemplate).query(any<String>(), any<RowCallbackHandler>(), eq("tenant-a|customer-42|2026-09"))
            verify(jdbcTemplate).query(any<String>(), any<RowCallbackHandler>(), eq("tenant-a|customer-42|2026-10"))
        }
    }
}

class ChargeTotalsTest {

    @Test
    fun `groups counts, quantities and amounts by transaction code without re-rounding`() {
        val charges = listOf(
            Fixtures.charge(Fixtures.usageTransaction(transactionCode = "VEHICLE_REGISTRATION", quantity = BigDecimal("2")), amount = "27.50"),
            Fixtures.charge(Fixtures.usageTransaction(transactionCode = "TITLE_TRANSFER", quantity = BigDecimal("1")), amount = "7.25"),
            Fixtures.charge(Fixtures.usageTransaction(transactionCode = "VEHICLE_REGISTRATION", quantity = BigDecimal("0.5")), amount = "6.88"),
        )

        val totals = ChargeTotals.byTransactionCode(charges)

        assertThat(totals).containsExactly(
            TransactionCodeTotal("TITLE_TRANSFER", 1, BigDecimal("1"), BigDecimal("7.25")),
            TransactionCodeTotal("VEHICLE_REGISTRATION", 2, BigDecimal("2.5"), BigDecimal("34.38")),
        )
        assertThat(ChargeTotals.total(totals)).isEqualTo(BigDecimal("41.63"))
    }

    @Test
    fun `an empty set of charges totals zero`() {
        assertThat(ChargeTotals.byTransactionCode(emptyList())).isEmpty()
        assertThat(ChargeTotals.total(emptyList())).isEqualByComparingTo("0")
    }
}
