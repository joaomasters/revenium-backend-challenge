package com.revenium.rating.ingestion

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Component
class ProcessingLog(
    private val processingLogRepository: ProcessingLogRepository,
    private val clock: Clock,
) {

    /** MANDATORY: a history entry must commit or roll back together with the state change it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(tenantId: String, transactionId: UUID, eventType: ProcessingEventType, detail: String? = null) {
        processingLogRepository.persist(
            ProcessingLogEntry(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                transactionId = transactionId,
                eventType = eventType,
                detail = detail,
                recordedAt = clock.instant(),
            )
        )
    }
}
