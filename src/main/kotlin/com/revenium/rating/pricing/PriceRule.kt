package com.revenium.rating.pricing

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Per-unit price for a tenant and transaction code over the half-open interval [effectiveFrom, effectiveTo).
 * Rules are immutable once created; the database forbids overlapping intervals for the same tenant and code.
 */
@Entity
@Table(name = "price_rule")
class PriceRule(
    @Id
    val id: UUID,
    val tenantId: String,
    val transactionCode: String,
    val unitPrice: BigDecimal,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val createdAt: Instant,
) {
    fun isEffectiveAt(instant: Instant): Boolean =
        !instant.isBefore(effectiveFrom) && (effectiveTo == null || instant.isBefore(effectiveTo))
}
