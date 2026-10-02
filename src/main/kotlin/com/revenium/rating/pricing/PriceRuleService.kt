package com.revenium.rating.pricing

import com.revenium.rating.common.ConflictException
import com.revenium.rating.common.InvalidRequestException
import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
@TenantScoped
class PriceRuleService(
    private val priceRuleRepository: PriceRuleRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(@TenantId tenantId: String): List<PriceRule> =
        priceRuleRepository.findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(tenantId)

    /**
     * The database exclusion constraint is the source of truth for "no overlapping rules": an application-level
     * check alone could not prevent two concurrent creates from both succeeding.
     */
    @Transactional
    fun create(
        @TenantId tenantId: String,
        transactionCode: String,
        unitPrice: BigDecimal,
        effectiveFrom: Instant,
        effectiveTo: Instant?,
    ): PriceRule {
        if (unitPrice.signum() < 0) throw InvalidRequestException("unitPrice must not be negative")
        if (unitPrice.stripTrailingZeros().scale() > MAX_PRICE_SCALE) {
            throw InvalidRequestException("unitPrice supports at most $MAX_PRICE_SCALE decimal places")
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw InvalidRequestException("effectiveTo must be after effectiveFrom")
        }
        val rule = PriceRule(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            transactionCode = transactionCode,
            unitPrice = unitPrice,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            createdAt = clock.instant(),
        )
        try {
            return priceRuleRepository.persistAndFlush(rule)
        } catch (ex: DataIntegrityViolationException) {
            if (isExclusionViolation(ex)) {
                throw ConflictException("A price rule for $transactionCode already covers part of this effective period")
            }
            throw ex
        }
    }

    private fun isExclusionViolation(ex: Throwable): Boolean =
        generateSequence(ex) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == EXCLUSION_VIOLATION }

    companion object {
        const val MAX_PRICE_SCALE = 6
        private const val EXCLUSION_VIOLATION = "23P01"
    }
}
