package com.revenium.rating

import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.ingestion.UsageTransaction
import com.revenium.rating.pricing.PriceRule
import com.revenium.rating.rating.RatedCharge
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

object Fixtures {

    const val TENANT = "tenant-a"
    const val CUSTOMER = "customer-42"
    val NOW: Instant = Instant.parse("2026-10-02T12:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    fun usageTransaction(
        eventId: String = UUID.randomUUID().toString(),
        transactionCode: String = "VEHICLE_REGISTRATION",
        occurredAt: Instant = Instant.parse("2026-08-15T14:22:31Z"),
        quantity: BigDecimal = BigDecimal("2"),
        status: TransactionStatus = TransactionStatus.PENDING,
        tenantId: String = TENANT,
        customerId: String = CUSTOMER,
    ) = UsageTransaction(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        eventId = eventId,
        customerId = customerId,
        transactionCode = transactionCode,
        occurredAt = occurredAt,
        quantity = quantity,
        fingerprint = "f".repeat(64),
        rawPayload = "{}",
        receivedAt = NOW,
        status = status,
    )

    fun priceRule(
        unitPrice: String = "13.75",
        from: String = "2026-08-01T00:00:00Z",
        to: String? = null,
        transactionCode: String = "VEHICLE_REGISTRATION",
    ) = PriceRule(
        id = UUID.randomUUID(),
        tenantId = TENANT,
        transactionCode = transactionCode,
        unitPrice = BigDecimal(unitPrice),
        effectiveFrom = Instant.parse(from),
        effectiveTo = to?.let(Instant::parse),
        createdAt = NOW,
    )

    fun charge(
        transaction: UsageTransaction = usageTransaction(),
        amount: String = "27.50",
        billingPeriod: LocalDate = LocalDate.of(2026, 8, 1),
        lateArrival: Boolean = false,
        invoiceId: UUID? = null,
    ) = RatedCharge(
        id = UUID.randomUUID(),
        tenantId = transaction.tenantId,
        transaction = transaction,
        priceRuleId = UUID.randomUUID(),
        unitPrice = BigDecimal("13.75"),
        amount = BigDecimal(amount),
        currency = "USD",
        billingPeriod = billingPeriod,
        lateArrival = lateArrival,
        ratedAt = NOW,
    ).also { charge -> invoiceId?.let(charge::assignToInvoice) }
}
