package com.revenium.rating

import com.revenium.rating.invoicing.InvoiceService
import com.revenium.rating.invoicing.IssuedInvoice
import com.revenium.rating.rating.RatingService
import com.revenium.rating.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Deterministic proof of the race the billing-period advisory lock exists for. One side's transaction is held
 * open with a latch while it owns the lock; the other side is observed waiting on the lock in pg_locks (not
 * inferred from a sleep); then the first side commits and the outcome is checked.
 */
class PeriodLockRaceIT : IntegrationTest() {

    @Autowired
    private lateinit var invoiceService: InvoiceService

    @Autowired
    private lateinit var ratingService: RatingService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private val august = YearMonth.of(2026, 8)
    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun shutDownPool() {
        pool.shutdownNow()
    }

    @Test
    fun `usage rated while the period's invoice is being issued waits for it and is billed in the next period`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-10T10:00:00Z" to "2")
        rateEverythingDue()

        // 1. The invoice transaction issues August and keeps the period lock until released.
        val invoiceIssued = CountDownLatch(1)
        val releaseInvoice = CountDownLatch(1)
        val invoice: Future<IssuedInvoice> = pool.submit<IssuedInvoice> {
            inTenantTransaction {
                invoiceService.issue("tenant-a", customer, august).also {
                    invoiceIssued.countDown()
                    check(releaseInvoice.await(30, TimeUnit.SECONDS))
                }
            }
        }
        check(invoiceIssued.await(30, TimeUnit.SECONDS))

        // 2. An August event arrives meanwhile (ingestion takes no period lock) and a worker starts rating it.
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer, occurredAt = "2026-08-20T10:00:00Z", quantity = "1"))
            .andExpect { status { isAccepted() } }
        val transactionId = transactionIdOf(eventId)
        val rating = pool.submit { ratingWorker.process("tenant-a", transactionId) }

        // 3. Rating is serialized behind the uncommitted invoice: it waits on the advisory lock.
        awaitAdvisoryLockWaiter()
        assertThat(rating.isDone).isFalse()
        assertThat(countRows("SELECT count(*) FROM rated_charge WHERE transaction_id = ?", transactionId)).isZero()

        // 4. The invoice commits; rating resumes, sees August closed and bills the next period.
        releaseInvoice.countDown()
        val issued = invoice.get(30, TimeUnit.SECONDS)
        rating.get(30, TimeUnit.SECONDS)

        val charge = jdbcTemplate.queryForMap(
            "SELECT billing_period, late_arrival, invoice_id FROM rated_charge WHERE transaction_id = ?", transactionId
        )
        assertThat(charge["billing_period"].toString()).isEqualTo("2026-09-01")
        assertThat(charge["late_arrival"]).isEqualTo(true)
        assertThat(charge["invoice_id"]).isNull()
        assertThat(issued.details.invoice.transactionCount).isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM rated_charge WHERE invoice_id = ?", issued.details.invoice.id)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject("SELECT total_amount FROM invoice WHERE id = ?", java.math.BigDecimal::class.java, issued.details.invoice.id)
        ).isEqualByComparingTo("27.50")
    }

    @Test
    fun `an invoice requested while a charge is being written waits for it and includes it`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer, occurredAt = "2026-08-10T10:00:00Z"))
            .andExpect { status { isAccepted() } }
        val transactionId = transactionIdOf(eventId)

        // 1. The rating transaction writes the August charge and keeps the period lock until released.
        val chargeWritten = CountDownLatch(1)
        val releaseRating = CountDownLatch(1)
        val rating = pool.submit {
            inTenantTransaction {
                ratingService.rate("tenant-a", transactionId)
                chargeWritten.countDown()
                check(releaseRating.await(30, TimeUnit.SECONDS))
            }
        }
        check(chargeWritten.await(30, TimeUnit.SECONDS))

        // 2. Invoicing August now must wait: the charge is not committed yet.
        val invoice: Future<IssuedInvoice> = pool.submit<IssuedInvoice> {
            TenantContext.runAs("tenant-a") { invoiceService.issue("tenant-a", customer, august) }
        }
        awaitAdvisoryLockWaiter()
        assertThat(invoice.isDone).isFalse()
        assertThat(countRows("SELECT count(*) FROM invoice WHERE customer_id = ?", customer)).isZero()

        // 3. Rating commits; the invoice then sees the transaction as RATED (not pending) and includes its charge.
        releaseRating.countDown()
        rating.get(30, TimeUnit.SECONDS)
        val issued = invoice.get(30, TimeUnit.SECONDS)

        assertThat(issued.created).isTrue()
        assertThat(issued.details.invoice.transactionCount).isEqualTo(1)
        assertThat(issued.details.invoice.totalAmount).isEqualByComparingTo("27.50")
        assertThat(jdbcTemplate.queryForObject("SELECT invoice_id FROM rated_charge WHERE transaction_id = ?", UUID::class.java, transactionId))
            .isEqualTo(issued.details.invoice.id)
    }

    private fun <T> inTenantTransaction(block: () -> T): T =
        TenantContext.runAs("tenant-a") { transactionTemplate.execute { block() }!! }

    private fun transactionIdOf(eventId: String): UUID =
        jdbcTemplate.queryForObject("SELECT id FROM usage_transaction WHERE event_id = ?", UUID::class.java, eventId)!!

    /** Waits until some session is blocked on an advisory lock: an observed condition, not a fixed sleep. */
    private fun awaitAdvisoryLockWaiter() {
        eventually { assertThat(countRows("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")).isEqualTo(1) }
    }
}
