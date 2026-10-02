package com.revenium.rating.ingestion

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The wire contract. Every field is nullable so that missing fields become validation errors, not parse errors.
 * Unknown fields are ignored so upstream producers can add fields without breaking ingestion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionPayload(
    val eventId: String? = null,
    val tenantId: String? = null,
    val customerId: String? = null,
    val transactionCode: String? = null,
    val occurredAt: String? = null,
    val quantity: BigDecimal? = null,
    val metadata: Map<String, Any?>? = null,
)

data class ValidTransaction(
    val eventId: String,
    val customerId: String,
    val transactionCode: String,
    val occurredAt: Instant,
    val quantity: BigDecimal,
) {
    /**
     * Identity of the billable content, used to tell a harmless re-delivery from a conflicting reuse of an eventId.
     * Metadata is deliberately excluded: a retry may legitimately arrive in a different upstream batch.
     */
    val fingerprint: String by lazy {
        val canonical = listOf(customerId, transactionCode, occurredAt.toString(), quantity.stripTrailingZeros().toPlainString())
            .joinToString("|") { "${it.length}:$it" }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

sealed interface ValidationResult {
    data class Valid(val transaction: ValidTransaction) : ValidationResult

    data class Invalid(
        val errors: List<String>,
        val tenantMismatch: Boolean = false,
        val eventId: String? = null,
        val customerId: String? = null,
        val occurredAt: Instant? = null,
    ) : ValidationResult
}

@Component
class TransactionValidator(private val jsonMapper: JsonMapper) {

    fun validate(rawPayload: String, authenticatedTenantId: String): ValidationResult {
        val payload = try {
            jsonMapper.readValue(rawPayload, TransactionPayload::class.java)
        } catch (ex: JacksonException) {
            return ValidationResult.Invalid(listOf("body: malformed JSON or field of the wrong type (${ex.originalMessage})"))
        } ?: return ValidationResult.Invalid(listOf("body: must be a JSON object"))

        val errors = mutableListOf<String>()
        val eventId = requireText(payload.eventId, "eventId", MAX_ID_LENGTH, errors)
        val tenantId = requireText(payload.tenantId, "tenantId", MAX_ID_LENGTH, errors)
        val customerId = requireText(payload.customerId, "customerId", MAX_ID_LENGTH, errors)
        val transactionCode = requireText(payload.transactionCode, "transactionCode", MAX_CODE_LENGTH, errors)
        val occurredAt = parseOccurredAt(payload.occurredAt, errors)
        val quantity = validateQuantity(payload.quantity, errors)

        val tenantMismatch = tenantId != null && tenantId != authenticatedTenantId
        if (tenantMismatch) errors += "tenantId: does not match the authenticated tenant"

        if (errors.isNotEmpty()) {
            return ValidationResult.Invalid(errors, tenantMismatch, eventId, customerId, occurredAt)
        }
        return ValidationResult.Valid(ValidTransaction(eventId!!, customerId!!, transactionCode!!, occurredAt!!, quantity!!))
    }

    private fun requireText(value: String?, field: String, maxLength: Int, errors: MutableList<String>): String? {
        when {
            value.isNullOrBlank() -> errors += "$field: is required"
            value.length > maxLength -> errors += "$field: must be at most $maxLength characters"
            else -> return value
        }
        return null
    }

    private fun parseOccurredAt(value: String?, errors: MutableList<String>): Instant? {
        if (value.isNullOrBlank()) {
            errors += "occurredAt: is required"
            return null
        }
        return try {
            // PostgreSQL stores microseconds; truncate up front so the stored value equals the validated one.
            OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS)
        } catch (ex: DateTimeParseException) {
            errors += "occurredAt: must be an ISO-8601 timestamp with offset, e.g. 2026-08-15T14:22:31Z"
            null
        }
    }

    private fun validateQuantity(value: BigDecimal?, errors: MutableList<String>): BigDecimal? {
        when {
            value == null -> errors += "quantity: is required"
            value.signum() <= 0 -> errors += "quantity: must be greater than zero"
            value.stripTrailingZeros().scale() > MAX_QUANTITY_SCALE -> errors += "quantity: at most $MAX_QUANTITY_SCALE decimal places"
            value.precision() - value.scale() > MAX_QUANTITY_INTEGER_DIGITS -> errors += "quantity: is too large"
            else -> return value
        }
        return null
    }

    companion object {
        const val MAX_ID_LENGTH = 128
        const val MAX_CODE_LENGTH = 64
        const val MAX_QUANTITY_SCALE = 6
        const val MAX_QUANTITY_INTEGER_DIGITS = 13
    }
}
