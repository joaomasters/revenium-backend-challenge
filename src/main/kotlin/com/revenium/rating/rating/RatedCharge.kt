package com.revenium.rating.rating

import com.revenium.rating.common.InsertOnlyRepository
import com.revenium.rating.ingestion.UsageTransaction
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The rating result for one accepted transaction: which rule was applied, the unit price it carried at the time,
 * the rounded amount, and the billing period it was assigned to. Immutable except for the link to its invoice.
 */
@Entity
@Table(name = "rated_charge")
class RatedCharge(
    @Id
    val id: UUID,
    val tenantId: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", updatable = false)
    val transaction: UsageTransaction,
    val priceRuleId: UUID,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val currency: String,
    val billingPeriod: LocalDate,
    val lateArrival: Boolean,
    val ratedAt: Instant,
) {
    var invoiceId: UUID? = null
        protected set

    fun assignToInvoice(invoiceId: UUID) {
        check(this.invoiceId == null) { "Charge $id is already on invoice ${this.invoiceId}" }
        this.invoiceId = invoiceId
    }
}

interface RatedChargeRepository : JpaRepository<RatedCharge, UUID>, InsertOnlyRepository<RatedCharge> {

    @Query(
        """
        select c from RatedCharge c join fetch c.transaction t
        where c.tenantId = :tenantId and t.customerId = :customerId
          and c.billingPeriod = :billingPeriod and c.invoiceId is null
        order by t.occurredAt
        """
    )
    fun findUninvoiced(tenantId: String, customerId: String, billingPeriod: LocalDate): List<RatedCharge>

    @Query(
        """
        select c from RatedCharge c join fetch c.transaction t
        where c.tenantId = :tenantId and c.invoiceId = :invoiceId
        order by t.occurredAt
        """
    )
    fun findByInvoice(tenantId: String, invoiceId: UUID): List<RatedCharge>

    @Query(
        """
        select c from RatedCharge c join fetch c.transaction t
        where c.tenantId = :tenantId and t.customerId = :customerId and t.occurredAt >= :from and t.occurredAt < :to
        order by t.occurredAt
        """
    )
    fun findForCustomer(tenantId: String, customerId: String, from: Instant, to: Instant): List<RatedCharge>
}
