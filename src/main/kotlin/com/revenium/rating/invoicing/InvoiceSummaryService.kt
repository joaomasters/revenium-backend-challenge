package com.revenium.rating.invoicing

import com.revenium.rating.common.InvalidRequestException
import com.revenium.rating.common.Money
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.rating.RatedChargeRepository
import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantRepository
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

data class InvoiceSummary(
    val customerId: String,
    val from: Instant,
    val to: Instant,
    val currency: String,
    val lines: List<TransactionCodeTotal>,
    val transactionCount: Int,
    val totalAmount: BigDecimal,
    /** Accepted transactions in the range that carry no charge yet (pending, unrated or failed). */
    val unratedTransactionCount: Int,
)

@Service
@TenantScoped
class InvoiceSummaryService(
    private val ratedChargeRepository: RatedChargeRepository,
    private val usageTransactionRepository: UsageTransactionRepository,
    private val tenantRepository: TenantRepository,
) {

    /**
     * Rated usage whose occurredAt falls in [from, to), whether or not it is invoiced yet. REPEATABLE READ gives
     * both queries the same snapshot, so the unrated count cannot drift from the charges while workers commit.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summarize(@TenantId tenantId: String, customerId: String, from: Instant, to: Instant): InvoiceSummary {
        if (!from.isBefore(to)) throw InvalidRequestException("'from' must be before 'to'")

        val charges = ratedChargeRepository.findForCustomer(tenantId, customerId, from, to)
        val acceptedCount = usageTransactionRepository.findForCustomer(tenantId, customerId, from, to).size
        val currency = tenantRepository.findById(tenantId).orElseThrow().currency
        val lines = ChargeTotals.byTransactionCode(charges)
        return InvoiceSummary(
            customerId = customerId,
            from = from,
            to = to,
            currency = currency,
            lines = lines.map { it.copy(amount = Money.inMinorUnits(it.amount, currency)) },
            transactionCount = charges.size,
            totalAmount = Money.inMinorUnits(ChargeTotals.total(lines), currency),
            unratedTransactionCount = acceptedCount - charges.size,
        )
    }
}
