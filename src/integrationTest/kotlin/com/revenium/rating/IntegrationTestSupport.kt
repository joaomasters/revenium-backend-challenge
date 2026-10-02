package com.revenium.rating

import com.revenium.rating.rating.RatingWorker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/** One PostgreSQL container per test JVM, shared by every Spring context (slices and full contexts). */
object SharedPostgres {
    val container: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine").apply { start() }
}

const val TENANT_A_KEY = "it-key-a"
const val TENANT_B_KEY = "it-key-b"

/**
 * Base for full-context tests. Not @Transactional: these tests need real commits to observe idempotency,
 * locking and after-commit behaviour. Isolation between tests comes from random customer and event ids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
abstract class IntegrationTest {

    companion object {
        @JvmField
        @ServiceConnection
        val postgres: PostgreSQLContainer = SharedPostgres.container
    }

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var ratingWorker: RatingWorker

    protected fun newCustomer() = "customer-${UUID.randomUUID()}"

    protected fun newEventId() = UUID.randomUUID().toString()

    protected fun transactionJson(
        eventId: String = newEventId(),
        customerId: String,
        tenantId: String = "tenant-a",
        transactionCode: String = "VEHICLE_REGISTRATION",
        occurredAt: String = "2026-08-15T14:22:31Z",
        quantity: String = "2",
        batchId: String = "batch-2026-08-15-01",
    ) = """
        {
          "eventId": "$eventId",
          "tenantId": "$tenantId", "customerId": "$customerId",
          "transactionCode": "$transactionCode",
          "occurredAt": "$occurredAt", "quantity": $quantity,
          "metadata": {"source": "upstream-api", "batchId": "$batchId"}
        }
    """.trimIndent()

    protected fun postTransaction(json: String, apiKey: String = TENANT_A_KEY): ResultActionsDsl =
        mockMvc.post("/api/v1/transactions") {
            header("X-API-Key", apiKey)
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    protected fun ingest(customerId: String, vararg events: Pair<String, String>, apiKey: String = TENANT_A_KEY) {
        for ((occurredAt, quantity) in events) {
            postTransaction(transactionJson(customerId = customerId, occurredAt = occurredAt, quantity = quantity), apiKey)
                .andExpect { status { isAccepted() } }
        }
    }

    /** Runs the rating worker until no due work is left. */
    protected fun rateEverythingDue() {
        while (ratingWorker.processDueBatch() > 0) {
            // drain
        }
    }

    protected fun issueInvoice(customerId: String, period: String, apiKey: String = TENANT_A_KEY): ResultActionsDsl =
        mockMvc.post("/api/v1/invoices") {
            header("X-API-Key", apiKey)
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId": "$customerId", "billingPeriod": "$period"}"""
        }

    protected fun reconciliation(customerId: String, from: String, to: String, apiKey: String = TENANT_A_KEY): ResultActionsDsl =
        mockMvc.get("/api/v1/reconciliation?customerId=$customerId&from=$from&to=$to") { header("X-API-Key", apiKey) }

    protected fun summary(customerId: String, from: String, to: String, apiKey: String = TENANT_A_KEY): ResultActionsDsl =
        mockMvc.get("/api/v1/invoices/summary?customerId=$customerId&from=$from&to=$to") { header("X-API-Key", apiKey) }

    protected fun countRows(sql: String, vararg args: Any): Int = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    /**
     * Injects a real database failure: while [block] runs, inserting a processing-log entry of [eventType] for
     * [customerId] raises an error inside PostgreSQL. This exercises genuine rollback paths without mocking
     * transactional proxies.
     */
    protected fun <T> withFailingProcessingLog(eventType: String, customerId: String, block: () -> T): T {
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION it_fail_processing_log() RETURNS trigger AS $$
            BEGIN
                IF NEW.event_type = '$eventType'
                   AND EXISTS (SELECT 1 FROM usage_transaction WHERE id = NEW.transaction_id AND customer_id = '$customerId') THEN
                    RAISE EXCEPTION 'simulated failure writing % log entry', NEW.event_type;
                END IF;
                RETURN NEW;
            END $$ LANGUAGE plpgsql
            """.trimIndent()
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER it_fail_processing_log BEFORE INSERT ON transaction_processing_log " +
                "FOR EACH ROW EXECUTE FUNCTION it_fail_processing_log()"
        )
        try {
            return block()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS it_fail_processing_log ON transaction_processing_log")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS it_fail_processing_log()")
        }
    }

    protected fun eventually(timeoutMillis: Long = 10_000, assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                if (System.currentTimeMillis() > deadline) throw error
                Thread.sleep(100)
            }
        }
    }
}
