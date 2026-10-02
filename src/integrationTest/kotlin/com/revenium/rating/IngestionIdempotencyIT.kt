package com.revenium.rating

import com.revenium.rating.ingestion.IngestionResult
import com.revenium.rating.ingestion.IngestionService
import com.revenium.rating.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IngestionIdempotencyIT : IntegrationTest() {

    @Autowired
    private lateinit var ingestionService: IngestionService

    @Test
    fun `accepts a transaction and durably stores the original payload before any processing`() {
        val customer = newCustomer()
        val eventId = newEventId()
        val json = transactionJson(eventId = eventId, customerId = customer)

        postTransaction(json).andExpect {
            status { isAccepted() }
            jsonPath("$.outcome") { value("ACCEPTED") }
            jsonPath("$.processingStatus") { value("PENDING") }
        }

        val row = jdbcTemplate.queryForMap("SELECT * FROM usage_transaction WHERE tenant_id = 'tenant-a' AND event_id = ?", eventId)
        assertThat(row["raw_payload"]).isEqualTo(json)
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat(countRows("SELECT count(*) FROM transaction_processing_log WHERE transaction_id = ? AND event_type = 'ACCEPTED'", row["id"]!!))
            .isEqualTo(1)
    }

    @Test
    fun `a re-delivery is reported as a duplicate and never creates a second transaction or charge`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer)).andExpect { status { isAccepted() } }
        rateEverythingDue()

        // Same billable content, different upstream batch: a legitimate retry.
        postTransaction(transactionJson(eventId = eventId, customerId = customer, quantity = "2.0", batchId = "retry-batch")).andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("DUPLICATE") }
            jsonPath("$.processingStatus") { value("RATED") }
        }
        rateEverythingDue()

        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE tenant_id = 'tenant-a' AND event_id = ?", eventId)).isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM rated_charge c JOIN usage_transaction t ON t.id = c.transaction_id WHERE t.event_id = ?", eventId))
            .isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM duplicate_delivery WHERE event_id = ? AND fingerprint_matches", eventId)).isEqualTo(1)
    }

    @Test
    fun `re-using an eventId with different billable content is a conflict and the original stands`() {
        val customer = newCustomer()
        val eventId = newEventId()
        val original = transactionJson(eventId = eventId, customerId = customer, quantity = "2")
        postTransaction(original).andExpect { status { isAccepted() } }

        postTransaction(transactionJson(eventId = eventId, customerId = customer, quantity = "5")).andExpect {
            status { isConflict() }
            jsonPath("$.outcome") { value("DUPLICATE_CONFLICT") }
        }

        val stored = jdbcTemplate.queryForMap("SELECT quantity, raw_payload FROM usage_transaction WHERE event_id = ?", eventId)
        assertThat(stored["quantity"].toString()).isEqualTo("2.000000")
        assertThat(stored["raw_payload"]).isEqualTo(original)
        assertThat(countRows("SELECT count(*) FROM duplicate_delivery WHERE event_id = ? AND NOT fingerprint_matches", eventId)).isEqualTo(1)
    }

    @Test
    fun `invalid submissions are rejected with every reason and kept as evidence`() {
        val customer = newCustomer()

        postTransaction("""{"tenantId": "tenant-a", "customerId": "$customer", "quantity": -1}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.outcome") { value("REJECTED") }
            jsonPath("$.errors.length()") { value(4) }
        }
        postTransaction("this is not json").andExpect { status { isBadRequest() } }

        assertThat(countRows("SELECT count(*) FROM rejected_submission WHERE customer_id = ?", customer)).isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM rejected_submission WHERE raw_payload = 'this is not json'")).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `concurrent deliveries of the same event produce exactly one accepted transaction`() {
        val customer = newCustomer()
        val eventId = newEventId()
        val json = transactionJson(eventId = eventId, customerId = customer)
        val threads = 12
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        val results = (1..threads).map {
            pool.submit<IngestionResult> {
                start.await()
                TenantContext.runAs("tenant-a") { ingestionService.ingest("tenant-a", json) }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes.filterIsInstance<IngestionResult.Accepted>()).hasSize(1)
        assertThat(outcomes.filterIsInstance<IngestionResult.Duplicate>()).hasSize(threads - 1)
        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE event_id = ?", eventId)).isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM duplicate_delivery WHERE event_id = ?", eventId)).isEqualTo(threads - 1)
    }

    @Test
    fun `oversized bodies are refused with 413 and leave no evidence rows behind`() {
        val customer = newCustomer()
        val padding = "x".repeat(70 * 1024)
        val oversized = transactionJson(customerId = customer).replace("\"upstream-api\"", "\"$padding\"")

        postTransaction(oversized).andExpect { status { isContentTooLarge() } }

        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE customer_id = ?", customer)).isZero()
        assertThat(countRows("SELECT count(*) FROM rejected_submission WHERE length(raw_payload) > 65536")).isZero()
    }

    @Test
    fun `requests without a valid api key are refused before any processing`() {
        val customer = newCustomer()

        postTransaction(transactionJson(customerId = customer), apiKey = "not-a-key").andExpect { status { isUnauthorized() } }

        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE customer_id = ?", customer)).isZero()
        assertThat(countRows("SELECT count(*) FROM rejected_submission WHERE customer_id = ?", customer)).isZero()
    }

    @Test
    fun `health endpoint is public`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
