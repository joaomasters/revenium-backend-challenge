package com.revenium.rating.invoicing

import com.revenium.rating.common.InsertOnlyRepository
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** An issued invoice. Immutable: issuing it closes the customer's billing period. */
@Entity
@Table(name = "invoice")
class Invoice(
    @Id
    val id: UUID,
    val tenantId: String,
    val customerId: String,
    val billingPeriod: LocalDate,
    val currency: String,
    val transactionCount: Int,
    val totalAmount: BigDecimal,
    val issuedAt: Instant,
    @OneToMany(cascade = [CascadeType.PERSIST], fetch = FetchType.EAGER)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    @OrderBy("transactionCode")
    val lines: List<InvoiceLine>,
)

@Entity
@Table(name = "invoice_line")
class InvoiceLine(
    @Id
    val id: UUID,
    val tenantId: String,
    val transactionCode: String,
    val transactionCount: Int,
    val quantity: BigDecimal,
    val amount: BigDecimal,
)

interface InvoiceRepository : JpaRepository<Invoice, UUID>, InsertOnlyRepository<Invoice> {

    fun existsByTenantIdAndCustomerIdAndBillingPeriod(tenantId: String, customerId: String, billingPeriod: LocalDate): Boolean

    fun findByTenantIdAndCustomerIdAndBillingPeriod(tenantId: String, customerId: String, billingPeriod: LocalDate): Invoice?

    fun findByTenantIdAndId(tenantId: String, id: UUID): Invoice?

    fun findByTenantIdAndCustomerIdOrderByBillingPeriod(tenantId: String, customerId: String): List<Invoice>
}
