package com.revenium.rating.invoicing

import com.revenium.rating.common.ConflictException
import com.revenium.rating.common.NotFoundException
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.rating.RatedCharge
import com.revenium.rating.rating.RatedChargeRepository
import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantRepository
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.YearMonth
import java.util.UUID

data class InvoiceDetails(val invoice: Invoice, val charges: List<RatedCharge>)

data class IssuedInvoice(val details: InvoiceDetails, val created: Boolean)

@Service
@TenantScoped
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val ratedChargeRepository: RatedChargeRepository,
    private val usageTransactionRepository: UsageTransactionRepository,
    private val tenantRepository: TenantRepository,
    private val billingPeriodGuard: BillingPeriodGuard,
    private val clock: Clock,
) {

    /**
     * Issues the invoice for a customer and an ended billing period, closing that period. Idempotent: issuing an
     * already-issued period returns the existing invoice. Holding the period lock means no charge can be added to
     * the period while its charges are collected; the unique constraint on (tenant, customer, period) backs this up.
     *
     * Usage that occurred in the period but is still PENDING blocks issuance: closing the period now would push
     * on-time usage into the next invoice just because rating is behind. UNRATED and FAILED do not block; they
     * need operator action and are visible in reconciliation.
     */
    @Transactional
    fun issue(@TenantId tenantId: String, customerId: String, period: YearMonth): IssuedInvoice {
        if (clock.instant().isBefore(BillingPeriods.end(period))) {
            throw ConflictException("Billing period $period has not ended yet")
        }
        billingPeriodGuard.lock(tenantId, customerId, period)

        invoiceRepository.findByTenantIdAndCustomerIdAndBillingPeriod(tenantId, customerId, period.atDay(1))?.let {
            return IssuedInvoice(InvoiceDetails(it, ratedChargeRepository.findByInvoice(tenantId, it.id)), created = false)
        }

        val pending = usageTransactionRepository.countForCustomer(
            tenantId, customerId, TransactionStatus.PENDING, BillingPeriods.start(period), BillingPeriods.end(period)
        )
        if (pending > 0) {
            throw ConflictException("$pending transaction(s) of $customerId in $period are still pending rating; retry once they are rated")
        }

        val currency = tenantRepository.findById(tenantId).orElseThrow().currency
        val charges = ratedChargeRepository.findUninvoiced(tenantId, customerId, period.atDay(1))
        check(charges.all { it.currency == currency }) { "Charges for $customerId in $period are not all in $currency" }

        val totals = ChargeTotals.byTransactionCode(charges)
        val invoice = invoiceRepository.persist(
            Invoice(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                customerId = customerId,
                billingPeriod = period.atDay(1),
                currency = currency,
                transactionCount = charges.size,
                totalAmount = ChargeTotals.total(totals),
                issuedAt = clock.instant(),
                lines = totals.map {
                    InvoiceLine(UUID.randomUUID(), tenantId, it.transactionCode, it.transactionCount, it.quantity, it.amount)
                },
            )
        )
        charges.forEach { it.assignToInvoice(invoice.id) }
        return IssuedInvoice(InvoiceDetails(invoice, charges), created = true)
    }

    @Transactional(readOnly = true)
    fun get(@TenantId tenantId: String, invoiceId: UUID): InvoiceDetails {
        val invoice = invoiceRepository.findByTenantIdAndId(tenantId, invoiceId)
            ?: throw NotFoundException("Invoice $invoiceId not found")
        return InvoiceDetails(invoice, ratedChargeRepository.findByInvoice(tenantId, invoiceId))
    }

    @Transactional(readOnly = true)
    fun list(@TenantId tenantId: String, customerId: String): List<Invoice> =
        invoiceRepository.findByTenantIdAndCustomerIdOrderByBillingPeriod(tenantId, customerId)
}
