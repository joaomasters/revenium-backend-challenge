package com.revenium.rating.ingestion

import com.fasterxml.jackson.annotation.JsonInclude
import com.revenium.rating.common.PayloadTooLargeException
import com.revenium.rating.rating.RatingService
import com.revenium.rating.tenancy.TenantContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.Charset
import java.util.UUID

enum class IngestionOutcome { ACCEPTED, DUPLICATE, DUPLICATE_CONFLICT, REJECTED, REQUEUED }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class IngestionResponse(
    val outcome: IngestionOutcome,
    val eventId: String? = null,
    val transactionId: UUID? = null,
    val processingStatus: TransactionStatus? = null,
    val rejectionId: UUID? = null,
    val errors: List<String>? = null,
)

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController(
    private val ingestionService: IngestionService,
    private val ratingService: RatingService,
    @Value("\${app.ingestion.max-payload-bytes}") private val maxPayloadBytes: Int,
) {

    /**
     * The body is taken as raw text so that even an unparseable submission can be stored as rejection evidence.
     *  - 202 ACCEPTED: stored durably, rating happens asynchronously
     *  - 200 DUPLICATE: eventId already accepted with the same billable content; nothing new is billed
     *  - 409 DUPLICATE_CONFLICT: eventId already accepted with different billable content; the original stands
     *  - 400 / 403 REJECTED: validation failed / payload tenantId differs from the authenticated tenant
     *  - 413: body larger than app.ingestion.max-payload-bytes; nothing is read further or stored
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun ingest(request: HttpServletRequest): ResponseEntity<IngestionResponse> =
        when (val result = ingestionService.ingest(TenantContext.requireTenantId(), readBoundedBody(request))) {
            is IngestionResult.Accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(
                IngestionResponse(IngestionOutcome.ACCEPTED, result.eventId, result.transactionId, TransactionStatus.PENDING)
            )
            is IngestionResult.Duplicate -> ResponseEntity.ok(
                IngestionResponse(IngestionOutcome.DUPLICATE, result.eventId, result.transactionId, result.processingStatus)
            )
            is IngestionResult.DuplicateConflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                IngestionResponse(
                    IngestionOutcome.DUPLICATE_CONFLICT,
                    result.eventId,
                    result.transactionId,
                    errors = listOf("eventId was already accepted with different customerId, transactionCode, occurredAt or quantity"),
                )
            )
            is IngestionResult.Rejected -> ResponseEntity
                .status(if (result.tenantMismatch) HttpStatus.FORBIDDEN else HttpStatus.BAD_REQUEST)
                .body(IngestionResponse(IngestionOutcome.REJECTED, rejectionId = result.rejectionId, errors = result.errors))
        }

    /**
     * Reads at most maxPayloadBytes + 1 bytes, so an oversized body (with or without a Content-Length header)
     * is never held in memory or persisted as rejection evidence.
     */
    private fun readBoundedBody(request: HttpServletRequest): String {
        if (request.contentLengthLong > maxPayloadBytes) throw PayloadTooLargeException(maxPayloadBytes)
        val bytes = request.inputStream.readNBytes(maxPayloadBytes + 1)
        if (bytes.size > maxPayloadBytes) throw PayloadTooLargeException(maxPayloadBytes)
        return String(bytes, request.characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8)
    }

    /** Re-queues an UNRATED or FAILED transaction. The earlier outcome stays in the processing log. */
    @PostMapping("/{eventId}/reprocess")
    fun reprocess(@PathVariable eventId: String): ResponseEntity<IngestionResponse> {
        val transaction = ratingService.requeue(TenantContext.requireTenantId(), eventId)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IngestionResponse(IngestionOutcome.REQUEUED, transaction.eventId, transaction.id, transaction.status))
    }
}
