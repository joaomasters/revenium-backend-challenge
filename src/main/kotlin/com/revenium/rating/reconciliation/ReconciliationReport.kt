package com.revenium.rating.reconciliation

import com.revenium.rating.common.Money
import com.revenium.rating.ingestion.DuplicateDelivery
import com.revenium.rating.ingestion.RejectedSubmission
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransaction
import com.revenium.rating.rating.RatedCharge
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/** Where an accepted transaction is in its lifecycle. INVOICED is derived from the charge's invoice link. */
enum class ReconciliationState { PENDING, UNRATED, FAILED, RATED, INVOICED }

data class ReconciliationCounts(
    /** Every delivery received for the customer and period: accepted + duplicate + rejected. */
    val received: Int,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Int,
    val pending: Int,
    val unrated: Int,
    val failed: Int,
    /** Rated but not yet on an invoice. */
    val rated: Int,
    val invoiced: Int,
)

data class ReconciledTransaction(
    val transactionId: UUID,
    val eventId: String,
    val transactionCode: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val quantity: BigDecimal,
    val state: ReconciliationState,
    val statusReason: String?,
    val attempts: Int,
    val priceRuleId: UUID?,
    val unitPrice: BigDecimal?,
    val amount: BigDecimal?,
    val billingPeriod: YearMonth?,
    val lateArrival: Boolean,
    val invoiceId: UUID?,
    val duplicateDeliveries: Int,
)

data class DuplicateEvidence(
    val duplicateId: UUID,
    val eventId: String,
    val transactionId: UUID,
    val receivedAt: Instant,
    val fingerprintMatches: Boolean,
)

data class RejectionEvidence(
    val rejectionId: UUID,
    val eventId: String?,
    val occurredAt: Instant?,
    val receivedAt: Instant,
    val reasons: String,
)

data class ReconciliationReport(
    val customerId: String,
    val from: Instant,
    val to: Instant,
    val currency: String,
    val counts: ReconciliationCounts,
    val ratedAmount: BigDecimal,
    val invoicedAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val transactions: List<ReconciledTransaction>,
    val duplicates: List<DuplicateEvidence>,
    val rejections: List<RejectionEvidence>,
) {
    companion object {

        fun assemble(
            customerId: String,
            from: Instant,
            to: Instant,
            currency: String,
            transactions: List<UsageTransaction>,
            charges: List<RatedCharge>,
            duplicates: List<DuplicateDelivery>,
            rejections: List<RejectedSubmission>,
        ): ReconciliationReport {
            val chargeByTransaction = charges.associateBy { it.transaction.id }
            val duplicatesByTransaction = duplicates.groupingBy { it.transactionId }.eachCount()
            val rows = transactions.map { reconcile(it, chargeByTransaction[it.id], duplicatesByTransaction[it.id] ?: 0) }

            val ratedAmount = Money.inMinorUnits(rows.filter { it.state == ReconciliationState.RATED }.sumOf { it.amount!! }, currency)
            val invoicedAmount = Money.inMinorUnits(rows.filter { it.state == ReconciliationState.INVOICED }.sumOf { it.amount!! }, currency)
            fun count(state: ReconciliationState) = rows.count { it.state == state }

            return ReconciliationReport(
                customerId = customerId,
                from = from,
                to = to,
                currency = currency,
                counts = ReconciliationCounts(
                    received = transactions.size + duplicates.size + rejections.size,
                    accepted = transactions.size,
                    duplicate = duplicates.size,
                    rejected = rejections.size,
                    pending = count(ReconciliationState.PENDING),
                    unrated = count(ReconciliationState.UNRATED),
                    failed = count(ReconciliationState.FAILED),
                    rated = count(ReconciliationState.RATED),
                    invoiced = count(ReconciliationState.INVOICED),
                ),
                ratedAmount = ratedAmount,
                invoicedAmount = invoicedAmount,
                totalAmount = ratedAmount + invoicedAmount,
                transactions = rows,
                duplicates = duplicates.map {
                    DuplicateEvidence(it.id, it.eventId, it.transactionId, it.receivedAt, it.fingerprintMatches)
                },
                rejections = rejections.map {
                    RejectionEvidence(it.id, it.eventId, it.occurredAt, it.receivedAt, it.reasons)
                },
            )
        }

        private fun reconcile(transaction: UsageTransaction, charge: RatedCharge?, duplicateCount: Int) =
            ReconciledTransaction(
                transactionId = transaction.id,
                eventId = transaction.eventId,
                transactionCode = transaction.transactionCode,
                occurredAt = transaction.occurredAt,
                receivedAt = transaction.receivedAt,
                quantity = transaction.quantity,
                state = stateOf(transaction, charge),
                statusReason = transaction.statusReason,
                attempts = transaction.attempts,
                priceRuleId = charge?.priceRuleId,
                unitPrice = charge?.unitPrice,
                amount = charge?.let { Money.inMinorUnits(it.amount, it.currency) },
                billingPeriod = charge?.let { YearMonth.from(it.billingPeriod) },
                lateArrival = charge?.lateArrival ?: false,
                invoiceId = charge?.invoiceId,
                duplicateDeliveries = duplicateCount,
            )

        private fun stateOf(transaction: UsageTransaction, charge: RatedCharge?): ReconciliationState = when {
            charge?.invoiceId != null -> ReconciliationState.INVOICED
            charge != null -> ReconciliationState.RATED
            transaction.status == TransactionStatus.UNRATED -> ReconciliationState.UNRATED
            transaction.status == TransactionStatus.FAILED -> ReconciliationState.FAILED
            else -> ReconciliationState.PENDING
        }
    }
}
