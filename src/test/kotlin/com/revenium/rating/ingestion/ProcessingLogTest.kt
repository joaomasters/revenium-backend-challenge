package com.revenium.rating.ingestion

import com.revenium.rating.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.UUID

class ProcessingLogTest {

    private val repository = mock<ProcessingLogRepository>()
    private val processingLog = ProcessingLog(repository, Fixtures.CLOCK)

    @Test
    fun `appends a timestamped entry for the transaction`() {
        val transactionId = UUID.randomUUID()

        processingLog.record(Fixtures.TENANT, transactionId, ProcessingEventType.UNRATED, "no rule")

        val entry = argumentCaptor<ProcessingLogEntry>().apply { verify(repository).persist(capture()) }.firstValue
        assertThat(entry.tenantId).isEqualTo(Fixtures.TENANT)
        assertThat(entry.transactionId).isEqualTo(transactionId)
        assertThat(entry.eventType).isEqualTo(ProcessingEventType.UNRATED)
        assertThat(entry.detail).isEqualTo("no rule")
        assertThat(entry.recordedAt).isEqualTo(Fixtures.NOW)
    }
}
