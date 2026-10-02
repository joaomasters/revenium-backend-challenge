package com.revenium.rating

import com.revenium.rating.invoicing.InvoiceService
import com.revenium.rating.invoicing.IssuedInvoice
import com.revenium.rating.rating.RatingOutcome
import com.revenium.rating.rating.RatingService
import com.revenium.rating.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RatingAndInvoicingIT : IntegrationTest() {

    @Autowired
    private lateinit var ratingService: RatingService

    @Autowired
    private lateinit var invoiceService: InvoiceService

    private val julyRule = "00000000-0000-0000-0000-00000000a001"
    private val augustRule = "00000000-0000-0000-0000-00000000a002"
    private val titleTransferRule = "00000000-0000-0000-0000-00000000a003"
    private val augustFrom = "2026-08-01T00:00:00Z"
    private val augustTo = "2026-09-01T00:00:00Z"

    @Test
    fun `rates asynchronously with the effective rule and summarizes by transaction code`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-15T14:22:31Z" to "2", "2026-08-20T09:00:00Z" to "1")
        postTransaction(transactionJson(customerId = customer, transactionCode = "TITLE_TRANSFER", occurredAt = "2026-08-21T10:00:00Z", quantity = "1"))
            .andExpect { status { isAccepted() } }

        summary(customer, augustFrom, augustTo).andExpect {
            jsonPath("$.transactionCount") { value(0) }
            jsonPath("$.unratedTransactionCount") { value(3) }
        }

        rateEverythingDue()

        summary(customer, augustFrom, augustTo).andExpect {
            status { isOk() }
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.lines.length()") { value(2) }
            jsonPath("$.lines[0].transactionCode") { value("TITLE_TRANSFER") }
            jsonPath("$.lines[0].amount") { value(7.25) }
            jsonPath("$.lines[1].transactionCode") { value("VEHICLE_REGISTRATION") }
            jsonPath("$.lines[1].transactionCount") { value(2) }
            jsonPath("$.lines[1].quantity") { value(3) }
            jsonPath("$.lines[1].amount") { value(41.25) }
            jsonPath("$.transactionCount") { value(3) }
            jsonPath("$.totalAmount") { value(48.50) }
            jsonPath("$.unratedTransactionCount") { value(0) }
        }
        reconciliation(customer, augustFrom, augustTo).andExpect {
            // Rows are ordered by occurredAt.
            jsonPath("$.transactions[0].transactionCode") { value("VEHICLE_REGISTRATION") }
            jsonPath("$.transactions[0].priceRuleId") { value(augustRule) }
            jsonPath("$.transactions[1].transactionCode") { value("VEHICLE_REGISTRATION") }
            jsonPath("$.transactions[1].priceRuleId") { value(augustRule) }
            jsonPath("$.transactions[2].transactionCode") { value("TITLE_TRANSFER") }
            jsonPath("$.transactions[2].priceRuleId") { value(titleTransferRule) }
        }
    }

    @Test
    fun `the rule change boundary is applied to the microsecond`() {
        val customer = newCustomer()
        ingest(customer, "2026-07-31T23:59:59.999999Z" to "1", "2026-08-01T00:00:00Z" to "1")

        rateEverythingDue()

        reconciliation(customer, "2026-07-01T00:00:00Z", "2026-09-01T00:00:00Z").andExpect {
            jsonPath("$.transactions[0].priceRuleId") { value(julyRule) }
            jsonPath("$.transactions[0].amount") { value(12.50) }
            jsonPath("$.transactions[0].billingPeriod") { value("2026-07") }
            jsonPath("$.transactions[1].priceRuleId") { value(augustRule) }
            jsonPath("$.transactions[1].amount") { value(13.75) }
            jsonPath("$.transactions[1].billingPeriod") { value("2026-08") }
        }
    }

    @Test
    fun `usage without a price rule stays unrated until a rule is added and it is reprocessed`() {
        val customer = newCustomer()
        val code = "CODE_${UUID.randomUUID().toString().take(8).uppercase()}"
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer, transactionCode = code)).andExpect { status { isAccepted() } }
        rateEverythingDue()

        reconciliation(customer, augustFrom, augustTo).andExpect {
            jsonPath("$.counts.unrated") { value(1) }
            jsonPath("$.transactions[0].statusReason") { value(org.hamcrest.Matchers.containsString(code)) }
        }

        mockMvc.post("/api/v1/pricing-rules") {
            header("X-API-Key", TENANT_A_KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"transactionCode": "$code", "unitPrice": 3.333333, "effectiveFrom": "2026-01-01T00:00:00Z"}"""
        }.andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/transactions/$eventId/reprocess") { header("X-API-Key", TENANT_A_KEY) }.andExpect {
            status { isAccepted() }
            jsonPath("$.outcome") { value("REQUEUED") }
        }
        rateEverythingDue()

        reconciliation(customer, augustFrom, augustTo).andExpect {
            jsonPath("$.counts.unrated") { value(0) }
            jsonPath("$.counts.rated") { value(1) }
            jsonPath("$.transactions[0].amount") { value(6.67) }
        }
        val history = jdbcTemplate.queryForList(
            "SELECT l.event_type FROM transaction_processing_log l JOIN usage_transaction t ON t.id = l.transaction_id " +
                "WHERE t.event_id = ? ORDER BY l.recorded_at",
            String::class.java,
            eventId,
        )
        assertThat(history).containsExactly("ACCEPTED", "UNRATED", "REQUEUED", "RATED")
    }

    @Test
    fun `issuing an invoice closes the period, is idempotent and links every charge`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-15T14:22:31Z" to "2", "2026-08-31T23:59:59Z" to "1", "2026-09-01T00:00:00Z" to "1")
        rateEverythingDue()

        var invoiceId: String? = null
        issueInvoice(customer, "2026-08").andExpect {
            status { isCreated() }
            jsonPath("$.invoice.transactionCount") { value(2) }
            jsonPath("$.invoice.totalAmount") { value(41.25) }
            jsonPath("$.lines[0].amount") { value(41.25) }
            jsonPath("$.charges.length()") { value(2) }
        }.andReturn().response.contentAsString.let { invoiceId = Regex("\"invoiceId\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

        issueInvoice(customer, "2026-08").andExpect {
            status { isOk() }
            jsonPath("$.invoice.invoiceId") { value(invoiceId) }
        }
        reconciliation(customer, augustFrom, "2026-10-01T00:00:00Z").andExpect {
            jsonPath("$.counts.invoiced") { value(2) }
            jsonPath("$.counts.rated") { value(1) }
            jsonPath("$.invoicedAmount") { value(41.25) }
            jsonPath("$.ratedAmount") { value(13.75) }
            jsonPath("$.totalAmount") { value(55.00) }
        }
    }

    @Test
    fun `usage still pending in the period blocks its invoice, while unrated usage and next-period usage do not`() {
        val customer = newCustomer()
        postTransaction(transactionJson(customerId = customer, transactionCode = "NO_RULE_CODE", occurredAt = "2026-08-05T10:00:00Z"))
            .andExpect { status { isAccepted() } }
        rateEverythingDue() // -> UNRATED; must not block
        ingest(customer, "2026-09-01T00:00:00Z" to "1") // first instant of September, still pending; must not block August
        ingest(customer, "2026-08-31T23:59:59.999999Z" to "1") // last instant of August, still pending; blocks August

        issueInvoice(customer, "2026-08").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("1 transaction(s)")) }
        }
        assertThat(countRows("SELECT count(*) FROM invoice WHERE customer_id = ?", customer)).isZero()

        rateEverythingDue()

        // The last-instant event is billed on time in August, not pushed into September.
        issueInvoice(customer, "2026-08").andExpect {
            status { isCreated() }
            jsonPath("$.invoice.transactionCount") { value(1) }
            jsonPath("$.charges[0].occurredAt") { value("2026-08-31T23:59:59.999999Z") }
            jsonPath("$.charges[0].lateArrival") { value(false) }
        }
    }

    @Test
    fun `monetary amounts are presented at the currency minor-unit scale`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-10T10:00:00Z" to "2")
        rateEverythingDue()

        val invoice = issueInvoice(customer, "2026-08").andReturn().response.contentAsString
        val report = reconciliation(customer, augustFrom, augustTo).andReturn().response.contentAsString
        val summary = summary(customer, augustFrom, augustTo).andReturn().response.contentAsString

        assertThat(invoice).contains("\"totalAmount\":27.50,", "\"amount\":27.50").doesNotContain("27.5000")
        assertThat(report).contains("\"amount\":27.50,", "\"invoicedAmount\":27.50,").doesNotContain("27.5000")
        assertThat(summary).contains("\"totalAmount\":27.50,").doesNotContain("27.5000")
    }

    @Test
    fun `a period that has not ended cannot be invoiced`() {
        issueInvoice(newCustomer(), "2026-10").andExpect { status { isConflict() } }
    }

    @Test
    fun `late arrivals are billed in the next open period and never change an issued invoice`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-10T10:00:00Z" to "2")
        rateEverythingDue()
        issueInvoice(customer, "2026-08").andExpect { status { isCreated() } }

        // Arrives after August was invoiced.
        ingest(customer, "2026-08-20T10:00:00Z" to "1")
        rateEverythingDue()

        reconciliation(customer, augustFrom, augustTo).andExpect {
            jsonPath("$.transactions[1].lateArrival") { value(true) }
            jsonPath("$.transactions[1].billingPeriod") { value("2026-09") }
            jsonPath("$.transactions[1].priceRuleId") { value(augustRule) }
        }
        issueInvoice(customer, "2026-08").andExpect {
            status { isOk() }
            jsonPath("$.invoice.totalAmount") { value(27.50) }
        }
        issueInvoice(customer, "2026-09").andExpect {
            status { isCreated() }
            jsonPath("$.invoice.totalAmount") { value(13.75) }
            jsonPath("$.charges[0].lateArrival") { value(true) }
            jsonPath("$.charges[0].occurredAt") { value("2026-08-20T10:00:00Z") }
        }
    }

    @Test
    fun `concurrent workers rate a transaction exactly once`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer)).andExpect { status { isAccepted() } }
        val transactionId = jdbcTemplate.queryForObject("SELECT id FROM usage_transaction WHERE event_id = ?", UUID::class.java, eventId)!!

        val outcomes = runConcurrently(8) { TenantContext.runAs("tenant-a") { ratingService.rate("tenant-a", transactionId) } }

        assertThat(outcomes.filterIsInstance<RatingOutcome.Rated>()).hasSize(1)
        assertThat(outcomes.filter { it == RatingOutcome.NotClaimed }).hasSize(7)
        assertThat(countRows("SELECT count(*) FROM rated_charge WHERE transaction_id = ?", transactionId)).isEqualTo(1)
    }

    @Test
    fun `concurrent invoice requests for the same period create exactly one invoice`() {
        val customer = newCustomer()
        ingest(customer, "2026-08-10T10:00:00Z" to "2")
        rateEverythingDue()

        val results = runConcurrently(6) {
            TenantContext.runAs("tenant-a") { invoiceService.issue("tenant-a", customer, YearMonth.of(2026, 8)) }
        }

        assertThat(results.count(IssuedInvoice::created)).isEqualTo(1)
        assertThat(results.map { it.details.invoice.id }.distinct()).hasSize(1)
        assertThat(countRows("SELECT count(*) FROM invoice WHERE customer_id = ?", customer)).isEqualTo(1)
    }

    @Test
    fun `reconciliation distinguishes every category and traces totals to events`() {
        val customer = newCustomer()
        val duplicated = newEventId()
        postTransaction(transactionJson(eventId = duplicated, customerId = customer, occurredAt = "2026-08-05T10:00:00Z")).andExpect { status { isAccepted() } }
        postTransaction(transactionJson(eventId = duplicated, customerId = customer, occurredAt = "2026-08-05T10:00:00Z")).andExpect { status { isOk() } }
        postTransaction(transactionJson(customerId = customer, transactionCode = "UNPRICED_CODE")).andExpect { status { isAccepted() } }
        postTransaction("""{"tenantId": "tenant-a", "customerId": "$customer", "occurredAt": "2026-08-07T00:00:00Z"}""")
            .andExpect { status { isBadRequest() } }
        rateEverythingDue()
        issueInvoice(customer, "2026-08").andExpect { status { isCreated() } }
        ingest(customer, "2026-08-25T10:00:00Z" to "1") // late arrival, rated into September
        postTransaction(transactionJson(customerId = customer, occurredAt = "2026-08-26T10:00:00Z")).andExpect { status { isAccepted() } }
        rateEverythingDue()
        ingest(customer, "2026-08-27T10:00:00Z" to "1") // still pending

        reconciliation(customer, augustFrom, augustTo).andExpect {
            status { isOk() }
            jsonPath("$.counts.received") { value(7) }
            jsonPath("$.counts.accepted") { value(5) }
            jsonPath("$.counts.duplicate") { value(1) }
            jsonPath("$.counts.rejected") { value(1) }
            jsonPath("$.counts.pending") { value(1) }
            jsonPath("$.counts.unrated") { value(1) }
            jsonPath("$.counts.rated") { value(2) }
            jsonPath("$.counts.invoiced") { value(1) }
            jsonPath("$.invoicedAmount") { value(27.50) }
            jsonPath("$.ratedAmount") { value(41.25) }
            jsonPath("$.totalAmount") { value(68.75) }
            jsonPath("$.transactions[0].duplicateDeliveries") { value(1) }
            jsonPath("$.rejections[0].reasons") { value(org.hamcrest.Matchers.containsString("quantity: is required")) }
        }
    }

    private fun <T> runConcurrently(threads: Int, task: () -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map { pool.submit<T> { start.await(); task() } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }
}
