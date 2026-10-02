package com.revenium.rating.rating

import com.revenium.rating.common.NotFoundException
import com.revenium.rating.ingestion.ProcessingEventType
import com.revenium.rating.ingestion.ProcessingLog
import com.revenium.rating.ingestion.TransactionQueuedEvent
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransaction
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.invoicing.BillingPeriodGuard
import com.revenium.rating.invoicing.BillingPeriods
import com.revenium.rating.pricing.PriceRuleRepository
import com.revenium.rating.pricing.RatingCalculator
import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantRepository
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

sealed interface RatingOutcome {
    /** Not pending any more, or currently being rated by another worker. */
    data object NotClaimed : RatingOutcome

    data class Rated(val charge: RatedCharge) : RatingOutcome

    data class Unrated(val reason: String) : RatingOutcome
}

@Service
@TenantScoped
class RatingService(
    private val usageTransactionRepository: UsageTransactionRepository,
    private val priceRuleRepository: PriceRuleRepository,
    private val ratedChargeRepository: RatedChargeRepository,
    private val tenantRepository: TenantRepository,
    private val billingPeriodGuard: BillingPeriodGuard,
    private val processingLog: ProcessingLog,
    private val eventPublisher: ApplicationEventPublisher,
    private val ratingProperties: RatingProperties,
    private val clock: Clock,
) {

    /**
     * Rates one transaction in a single transaction (READ COMMITTED plus explicit row locks):
     * claim the row with FOR UPDATE SKIP LOCKED, resolve the effective rule, lock the billing period, insert the
     * charge, mark the row RATED. Any exception rolls all of it back and the row stays PENDING for a retry.
     * The unique constraint on rated_charge.transaction_id backs up the claim against double rating.
     */
    @Transactional
    fun rate(@TenantId tenantId: String, transactionId: UUID): RatingOutcome {
        val transaction = usageTransactionRepository.claimPending(tenantId, transactionId) ?: return RatingOutcome.NotClaimed
        val now = clock.instant()

        val rule = priceRuleRepository.findByTenantIdAndTransactionCodeOrderByEffectiveFrom(tenantId, transaction.transactionCode)
            .singleOrNull { it.isEffectiveAt(transaction.occurredAt) }
        if (rule == null) {
            val reason = "No price rule for ${transaction.transactionCode} effective at ${transaction.occurredAt}"
            transaction.markUnrated(reason, now)
            processingLog.record(tenantId, transaction.id, ProcessingEventType.UNRATED, reason)
            return RatingOutcome.Unrated(reason)
        }

        val currency = tenantRepository.findById(tenantId).orElseThrow().billingCurrency()
        val usagePeriod = BillingPeriods.containing(transaction.occurredAt)
        val billingPeriod = billingPeriodGuard.lockFirstOpenPeriod(tenantId, transaction.customerId, usagePeriod)
        val charge = ratedChargeRepository.persistAndFlush(
            RatedCharge(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                transaction = transaction,
                priceRuleId = rule.id,
                unitPrice = rule.unitPrice,
                amount = RatingCalculator.amount(transaction.quantity, rule.unitPrice, currency),
                currency = currency.currencyCode,
                billingPeriod = billingPeriod.atDay(1),
                lateArrival = billingPeriod != usagePeriod,
                ratedAt = now,
            )
        )
        transaction.markRated(now)
        processingLog.record(
            tenantId,
            transaction.id,
            ProcessingEventType.RATED,
            "rule=${rule.id} amount=${charge.amount} ${charge.currency} billingPeriod=$billingPeriod" +
                if (charge.lateArrival) " (late arrival from $usagePeriod)" else "",
        )
        return RatingOutcome.Rated(charge)
    }

    /**
     * Runs in its own transaction after [rate] has rolled back, so the failed attempt is recorded even though the
     * rating work was discarded. After maxAttempts the event is parked as FAILED instead of retrying forever.
     */
    @Transactional
    fun recordFailure(@TenantId tenantId: String, transactionId: UUID, error: String) {
        val transaction = usageTransactionRepository.lockById(tenantId, transactionId) ?: return
        if (transaction.status != TransactionStatus.PENDING) return
        val parked = transaction.recordFailedAttempt(
            error.take(MAX_ERROR_LENGTH),
            clock.instant(),
            ratingProperties.maxAttempts,
            ratingProperties.retryBackoff,
        )
        processingLog.record(
            tenantId,
            transactionId,
            if (parked) ProcessingEventType.FAILED else ProcessingEventType.ATTEMPT_FAILED,
            error.take(MAX_ERROR_LENGTH),
        )
    }

    /** Puts an UNRATED or FAILED transaction back in the queue, e.g. after the missing price rule was added. */
    @Transactional
    fun requeue(@TenantId tenantId: String, eventId: String): UsageTransaction {
        val transaction = usageTransactionRepository.lockByEventId(tenantId, eventId)
            ?: throw NotFoundException("Transaction with eventId $eventId not found")
        val previousStatus = transaction.status
        transaction.requeue(clock.instant())
        processingLog.record(tenantId, transaction.id, ProcessingEventType.REQUEUED, "previous status $previousStatus")
        eventPublisher.publishEvent(TransactionQueuedEvent(tenantId, transaction.id))
        return transaction
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 2000
    }
}
