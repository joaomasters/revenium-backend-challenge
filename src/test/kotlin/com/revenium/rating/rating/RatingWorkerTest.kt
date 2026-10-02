package com.revenium.rating.rating

import com.revenium.rating.Fixtures
import com.revenium.rating.ingestion.TransactionQueuedEvent
import com.revenium.rating.ingestion.TransactionRef
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class RatingWorkerTest {

    private val transactions = mock<UsageTransactionRepository>()
    private val ratingService = mock<RatingService>()
    private val properties = RatingProperties(batchSize = 2)
    private val worker = RatingWorker(transactions, ratingService, properties, Fixtures.CLOCK)

    @Test
    fun `binds each item's own tenant while rating it`() {
        val tenantA = TransactionRef("tenant-a", UUID.randomUUID())
        val tenantB = TransactionRef("tenant-b", UUID.randomUUID())
        whenever(transactions.findDueForRating(eq(Fixtures.NOW), argThat { max() == 2 })).thenReturn(listOf(tenantA, tenantB))
        val observedTenants = mutableListOf<String?>()
        whenever(ratingService.rate(any(), any())).doAnswer {
            observedTenants += TenantContext.currentTenantId()
            RatingOutcome.NotClaimed
        }

        assertThat(worker.processDueBatch()).isEqualTo(2)

        assertThat(observedTenants).containsExactly("tenant-a", "tenant-b")
        assertThat(TenantContext.currentTenantId()).isNull()
    }

    @Test
    fun `records a failure when rating throws and carries on with the batch`() {
        val first = TransactionRef(Fixtures.TENANT, UUID.randomUUID())
        val second = TransactionRef(Fixtures.TENANT, UUID.randomUUID())
        whenever(transactions.findDueForRating(any(), any())).thenReturn(listOf(first, second))
        whenever(ratingService.rate(Fixtures.TENANT, first.id)).thenThrow(IllegalStateException("broken"))
        whenever(ratingService.rate(Fixtures.TENANT, second.id)).thenReturn(RatingOutcome.NotClaimed)

        worker.processDueBatch()

        verify(ratingService).recordFailure(eq(Fixtures.TENANT), eq(first.id), argThat { contains("broken") })
        verify(ratingService).rate(Fixtures.TENANT, second.id)
        verify(ratingService, never()).recordFailure(any(), eq(second.id), any())
    }

    @Test
    fun `scheduler keeps draining while batches come back full`() {
        val worker = mock<RatingWorker>()
        whenever(worker.processDueBatch()).thenReturn(2, 2, 1)

        RatingScheduler(worker, properties).drain()

        verify(worker, times(3)).processDueBatch()
    }

    @Test
    fun `dispatcher rates the queued transaction`() {
        val worker = mock<RatingWorker>()
        val id = UUID.randomUUID()

        RatingDispatcher(worker).onTransactionQueued(TransactionQueuedEvent(Fixtures.TENANT, id))

        verify(worker).process(Fixtures.TENANT, id)
    }
}
