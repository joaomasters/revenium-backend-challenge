package com.revenium.rating

import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.invoicing.Invoice
import com.revenium.rating.invoicing.InvoiceRepository
import com.revenium.rating.pricing.PriceRule
import com.revenium.rating.pricing.PriceRuleRepository
import com.revenium.rating.rating.RatedCharge
import com.revenium.rating.rating.RatedChargeRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * JPA slice against real PostgreSQL with the Liquibase schema: proves the invariants the database enforces on
 * its own, independently of any service code. Each test runs in a transaction that is rolled back.
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=validate"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
class PersistenceInvariantsTest(
    @Autowired private val transactions: UsageTransactionRepository,
    @Autowired private val priceRules: PriceRuleRepository,
    @Autowired private val charges: RatedChargeRepository,
    @Autowired private val invoices: InvoiceRepository,
    @Autowired private val entityManager: EntityManager,
) {

    companion object {
        @JvmField
        @ServiceConnection
        val postgres: PostgreSQLContainer = SharedPostgres.container

        private val TENANT_A_AUGUST_RULE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a002")
        private val TENANT_B_RULE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b001")
    }

    private fun insert(tenantId: String = "tenant-a", eventId: String = UUID.randomUUID().toString(), quantity: String = "2"): Int =
        transactions.insertIfAbsent(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            eventId = eventId,
            customerId = "customer-42",
            transactionCode = "VEHICLE_REGISTRATION",
            occurredAt = Instant.parse("2026-08-15T14:22:31Z"),
            quantity = BigDecimal(quantity),
            fingerprint = "f".repeat(64),
            rawPayload = "{}",
            receivedAt = Instant.parse("2026-08-15T14:22:32Z"),
        )

    private fun rule(transactionCode: String, from: String, to: String?, tenantId: String = "tenant-a") = PriceRule(
        UUID.randomUUID(), tenantId, transactionCode, BigDecimal("1.00"), Instant.parse(from), to?.let(Instant::parse), Instant.now(),
    )

    private fun chargeFor(eventId: String, tenantId: String, priceRuleId: UUID) = RatedCharge(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        transaction = transactions.findByTenantIdAndEventId("tenant-a", eventId)!!,
        priceRuleId = priceRuleId,
        unitPrice = BigDecimal("13.75"),
        amount = BigDecimal("27.50"),
        currency = "USD",
        billingPeriod = LocalDate.of(2026, 8, 1),
        lateArrival = false,
        ratedAt = Instant.now(),
    )

    @Test
    fun `liquibase applies the schema and the seed price rules`() {
        val rules = priceRules.findByTenantIdAndTransactionCodeOrderByEffectiveFrom("tenant-a", "VEHICLE_REGISTRATION")

        assertThat(rules.map { it.unitPrice }).containsExactly(BigDecimal("12.500000"), BigDecimal("13.750000"))
        assertThat(rules[0].effectiveTo).isEqualTo(rules[1].effectiveFrom)
    }

    @Test
    fun `the idempotency key is unique per tenant, not globally`() {
        val eventId = UUID.randomUUID().toString()

        assertThat(insert(eventId = eventId)).isEqualTo(1)
        assertThat(insert(eventId = eventId)).isEqualTo(0)
        assertThat(insert(tenantId = "tenant-b", eventId = eventId)).isEqualTo(1)
    }

    @Test
    fun `the database rejects non-positive quantities`() {
        assertThatThrownBy { insert(quantity = "0") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `tenant-scoped lookups never return another tenant's rows`() {
        val eventId = UUID.randomUUID().toString()
        insert(eventId = eventId)

        assertThat(transactions.findByTenantIdAndEventId("tenant-b", eventId)).isNull()
        assertThat(
            transactions.findForCustomer("tenant-b", "customer-42", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))
        ).isEmpty()
    }

    @Test
    fun `overlapping price rules are rejected by the exclusion constraint`() {
        priceRules.persistAndFlush(rule("OVERLAP_TEST", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z"))

        assertThatThrownBy { priceRules.persistAndFlush(rule("OVERLAP_TEST", "2026-06-30T00:00:00Z", null)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `adjacent price rules and the same code in another tenant are allowed`() {
        priceRules.persistAndFlush(rule("ADJACENT_TEST", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z"))
        priceRules.persistAndFlush(rule("ADJACENT_TEST", "2026-07-01T00:00:00Z", null))
        priceRules.persistAndFlush(rule("ADJACENT_TEST", "2026-01-01T00:00:00Z", null, tenantId = "tenant-b"))

        assertThat(priceRules.findByTenantIdAndTransactionCodeOrderByEffectiveFrom("tenant-a", "ADJACENT_TEST")).hasSize(2)
    }

    @Test
    fun `a transaction can carry at most one charge`() {
        val eventId = UUID.randomUUID().toString()
        insert(eventId = eventId)
        charges.persistAndFlush(chargeFor(eventId, "tenant-a", TENANT_A_AUGUST_RULE))

        assertThatThrownBy { charges.persistAndFlush(chargeFor(eventId, "tenant-a", TENANT_A_AUGUST_RULE)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a charge cannot reference another tenant's price rule`() {
        val eventId = UUID.randomUUID().toString()
        insert(eventId = eventId)

        assertThatThrownBy { charges.persistAndFlush(chargeFor(eventId, "tenant-a", TENANT_B_RULE)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a charge cannot be filed under another tenant than its transaction`() {
        val eventId = UUID.randomUUID().toString()
        insert(eventId = eventId)

        assertThatThrownBy { charges.persistAndFlush(chargeFor(eventId, "tenant-b", TENANT_B_RULE)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a customer's billing period can be invoiced only once`() {
        fun invoice() = Invoice(
            UUID.randomUUID(), "tenant-a", "customer-unique", LocalDate.of(2026, 8, 1), "USD", 0, BigDecimal.ZERO, Instant.now(), emptyList()
        )
        invoices.persistAndFlush(invoice())

        assertThatThrownBy { invoices.persistAndFlush(invoice()) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `persist refuses to overwrite an existing row instead of merging silently`() {
        val existing = priceRules.findById(TENANT_A_AUGUST_RULE).orElseThrow()
        entityManager.detach(existing)
        val forged = PriceRule(existing.id, "tenant-a", "VEHICLE_REGISTRATION", BigDecimal("0.01"), existing.effectiveFrom, null, Instant.now())

        assertThatThrownBy { priceRules.persistAndFlush(forged) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
