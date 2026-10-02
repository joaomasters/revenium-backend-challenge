package com.revenium.rating.invoicing

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

/**
 * Serializes "assign a charge to a period" (rating) against "close a period" (invoice issuance) for one
 * tenant + customer + month, using a transaction-scoped PostgreSQL advisory lock.
 *
 * Without it, a charge could be committed into a period just after its invoice had read the period's charges,
 * leaving a rated charge that belongs to an issued period but is on no invoice. With it, either the charge
 * commits first and is invoiced, or the invoice commits first and the charge sees the period as closed.
 *
 * The lock works across application instances because it lives in the database, and is released automatically
 * at commit or rollback. A hash collision between two keys only causes extra waiting, never incorrect results.
 */
@Component
class BillingPeriodGuard(
    private val jdbcTemplate: JdbcTemplate,
    private val invoiceRepository: InvoiceRepository,
) {

    /** MANDATORY: a transaction-scoped lock taken outside a transaction would be released immediately. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(tenantId: String, customerId: String, period: YearMonth) {
        jdbcTemplate.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            RowCallbackHandler { },
            "$tenantId|$customerId|$period",
        )
    }

    /**
     * Late-arrival policy: usage whose own period is already invoiced is billed in the first later period that
     * has no invoice yet. Locks are taken in ascending period order, so concurrent callers cannot deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockFirstOpenPeriod(tenantId: String, customerId: String, usagePeriod: YearMonth): YearMonth {
        var candidate = usagePeriod
        while (true) {
            lock(tenantId, customerId, candidate)
            if (!invoiceRepository.existsByTenantIdAndCustomerIdAndBillingPeriod(tenantId, customerId, candidate.atDay(1))) {
                return candidate
            }
            candidate = candidate.plusMonths(1)
        }
    }
}
