package com.revenium.rating

import com.revenium.rating.ingestion.IngestionService
import com.revenium.rating.ingestion.ProcessingEventType
import com.revenium.rating.ingestion.ProcessingLog
import com.revenium.rating.rating.RatingWorker
import com.revenium.rating.reconciliation.ReconciliationService
import com.revenium.rating.tenancy.MissingTenantContextException
import com.revenium.rating.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.util.UUID

/** Proves AOP application and ordering, rollback, MANDATORY propagation and retry recording. */
class TransactionBoundariesIT : IntegrationTest() {

    @MockitoSpyBean
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var processingLog: ProcessingLog

    @Autowired
    private lateinit var reconciliationService: ReconciliationService

    @Autowired
    private lateinit var ingestionService: IngestionService

    @Test
    fun `tenant-scoped services are AOP proxies and the tenant check runs before any transaction is opened`() {
        assertThat(AopUtils.isAopProxy(reconciliationService)).isTrue()
        clearInvocations(transactionManager)

        assertThatThrownBy {
            reconciliationService.report("tenant-a", "c", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))
        }.isInstanceOf(MissingTenantContextException::class.java)

        verify(transactionManager, never()).getTransaction(any())
    }

    @Test
    fun `a failure after the charge insert rolls back the whole rating and is recorded as a retryable attempt`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer)).andExpect { status { isAccepted() } }
        val transactionId = jdbcTemplate.queryForObject("SELECT id FROM usage_transaction WHERE event_id = ?", UUID::class.java, eventId)!!

        // The charge row is flushed first; the RATED log entry then fails inside PostgreSQL at commit.
        withFailingProcessingLog("RATED", customer) { ratingWorker.process("tenant-a", transactionId) }

        assertThat(countRows("SELECT count(*) FROM rated_charge WHERE transaction_id = ?", transactionId)).isZero()
        val row = jdbcTemplate.queryForMap("SELECT status, attempts, status_reason FROM usage_transaction WHERE id = ?", transactionId)
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat(row["attempts"]).isEqualTo(1)
        assertThat(row["status_reason"].toString()).contains("simulated failure")
        assertThat(countRows("SELECT count(*) FROM transaction_processing_log WHERE transaction_id = ? AND event_type = 'ATTEMPT_FAILED'", transactionId))
            .isEqualTo(1)
        assertThat(countRows("SELECT count(*) FROM transaction_processing_log WHERE transaction_id = ? AND event_type = 'RATED'", transactionId))
            .isZero()

        // The retry succeeds and produces exactly one charge.
        ratingWorker.process("tenant-a", transactionId)

        assertThat(countRows("SELECT count(*) FROM rated_charge WHERE transaction_id = ?", transactionId)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM usage_transaction WHERE id = ?", String::class.java, transactionId))
            .isEqualTo("RATED")
    }

    @Test
    fun `a failure inside ingestion rolls back the accepted row, so nothing is left to rate`() {
        val customer = newCustomer()

        withFailingProcessingLog("ACCEPTED", customer) {
            assertThatThrownBy {
                TenantContext.runAs("tenant-a") { ingestionService.ingest("tenant-a", transactionJson(customerId = customer)) }
            }.isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("simulated failure")
        }

        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE customer_id = ?", customer)).isZero()
    }

    @Test
    fun `history entries can only be written inside the transaction of the change they describe`() {
        assertThatThrownBy { processingLog.record("tenant-a", UUID.randomUUID(), ProcessingEventType.RATED) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }
}

/** With the after-commit fast path enabled: work is dispatched only once the ingesting transaction has committed. */
@TestPropertySource(properties = ["app.rating.dispatch-on-commit=true"])
class AfterCommitDispatchIT : IntegrationTest() {

    @MockitoSpyBean
    private lateinit var worker: RatingWorker

    @Test
    fun `a committed ingestion is rated asynchronously without waiting for the scheduler`() {
        val customer = newCustomer()
        val eventId = newEventId()

        postTransaction(transactionJson(eventId = eventId, customerId = customer)).andExpect {
            status { isAccepted() }
            jsonPath("$.processingStatus") { value("PENDING") }
        }

        eventually {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM usage_transaction WHERE event_id = ?", String::class.java, eventId))
                .isEqualTo("RATED")
        }
    }

    @Test
    fun `a rolled-back ingestion never dispatches rating work`() {
        val customer = newCustomer()

        withFailingProcessingLog("ACCEPTED", customer) {
            assertThatThrownBy { postTransaction(transactionJson(customerId = customer)) }.rootCause().hasMessageContaining("simulated failure")
        }

        Thread.sleep(500)
        verify(worker, never()).process(any(), any())
        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE customer_id = ?", customer)).isZero()
    }
}
