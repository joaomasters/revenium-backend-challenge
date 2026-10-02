package com.revenium.rating.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.Instant

class TransactionValidatorTest {

    private val validator = TransactionValidator(JsonMapper.builder().addModule(kotlinModule()).build())

    private fun payload(
        eventId: String? = "\"73d4e120-77d0-4f11-a6d2-f3b43b430d9c\"",
        tenantId: String? = "\"tenant-a\"",
        customerId: String? = "\"customer-42\"",
        transactionCode: String? = "\"VEHICLE_REGISTRATION\"",
        occurredAt: String? = "\"2026-08-15T14:22:31Z\"",
        quantity: String? = "2",
        metadata: String? = """{"source": "upstream-api", "batchId": "batch-2026-08-15-01"}""",
    ): String = listOf(
        "eventId" to eventId,
        "tenantId" to tenantId,
        "customerId" to customerId,
        "transactionCode" to transactionCode,
        "occurredAt" to occurredAt,
        "quantity" to quantity,
        "metadata" to metadata,
    ).filter { it.second != null }.joinToString(",", "{", "}") { "\"${it.first}\": ${it.second}" }

    private fun validate(json: String) = validator.validate(json, "tenant-a")

    private fun errorsOf(json: String): List<String> = (validate(json) as ValidationResult.Invalid).errors

    @Test
    fun `accepts the sample transaction from the specification`() {
        val result = validate(payload()) as ValidationResult.Valid

        with(result.transaction) {
            assertThat(eventId).isEqualTo("73d4e120-77d0-4f11-a6d2-f3b43b430d9c")
            assertThat(customerId).isEqualTo("customer-42")
            assertThat(transactionCode).isEqualTo("VEHICLE_REGISTRATION")
            assertThat(occurredAt).isEqualTo(Instant.parse("2026-08-15T14:22:31Z"))
            assertThat(quantity).isEqualByComparingTo("2")
        }
    }

    @Test
    fun `metadata is optional and unknown fields are ignored`() {
        val json = payload(metadata = null).dropLast(1) + ", \"futureField\": true}"

        assertThat(validate(json)).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `reports every missing required field at once`() {
        val errors = errorsOf("{}")

        assertThat(errors).containsExactlyInAnyOrder(
            "eventId: is required",
            "tenantId: is required",
            "customerId: is required",
            "transactionCode: is required",
            "occurredAt: is required",
            "quantity: is required",
        )
    }

    @Test
    fun `blank strings count as missing`() {
        assertThat(errorsOf(payload(customerId = "\"   \""))).containsExactly("customerId: is required")
    }

    @Test
    fun `rejects identifiers longer than the stored column`() {
        assertThat(errorsOf(payload(eventId = "\"${"x".repeat(129)}\""))).containsExactly("eventId: must be at most 128 characters")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "0.0"])
    fun `rejects non-positive quantities`(quantity: String) {
        assertThat(errorsOf(payload(quantity = quantity))).containsExactly("quantity: must be greater than zero")
    }

    @Test
    fun `rejects quantities with more than six decimals`() {
        assertThat(errorsOf(payload(quantity = "1.0000001"))).containsExactly("quantity: at most 6 decimal places")
    }

    @Test
    fun `accepts trailing zeros beyond six decimals because the value is still exact`() {
        assertThat(validate(payload(quantity = "1.50000000"))).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `rejects quantities that do not fit the column`() {
        assertThat(errorsOf(payload(quantity = "12345678901234"))).containsExactly("quantity: is too large")
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"2026-08-15T14:22:31\"", "\"2026-08-15\"", "\"yesterday\""])
    fun `requires an ISO-8601 timestamp with an offset`(occurredAt: String) {
        assertThat(errorsOf(payload(occurredAt = occurredAt))).singleElement().asString().startsWith("occurredAt:")
    }

    @Test
    fun `normalizes offsets to UTC and truncates to microseconds`() {
        val result = validate(payload(occurredAt = "\"2026-08-15T16:22:31.123456789+02:00\"")) as ValidationResult.Valid

        assertThat(result.transaction.occurredAt).isEqualTo(Instant.parse("2026-08-15T14:22:31.123456Z"))
    }

    @Test
    fun `flags a payload tenant that differs from the authenticated tenant`() {
        val result = validate(payload(tenantId = "\"tenant-b\"")) as ValidationResult.Invalid

        assertThat(result.tenantMismatch).isTrue()
        assertThat(result.errors).containsExactly("tenantId: does not match the authenticated tenant")
        assertThat(result.eventId).isEqualTo("73d4e120-77d0-4f11-a6d2-f3b43b430d9c")
        assertThat(result.customerId).isEqualTo("customer-42")
    }

    @Test
    fun `keeps parseable identity fields of an invalid payload for the rejection record`() {
        val result = validate(payload(quantity = "0")) as ValidationResult.Invalid

        assertThat(result.tenantMismatch).isFalse()
        assertThat(result.occurredAt).isEqualTo(Instant.parse("2026-08-15T14:22:31Z"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["not json", "{\"quantity\": \"abc\"}", "[1, 2]", "{\"eventId\": "])
    fun `rejects bodies that cannot be read as a transaction`(body: String) {
        assertThat(errorsOf(body)).singleElement().asString().startsWith("body:")
    }

    @Test
    fun `rejects a JSON null body`() {
        assertThat(errorsOf("null")).containsExactly("body: must be a JSON object")
    }

    @Test
    fun `fingerprint ignores metadata and the scale of the quantity`() {
        val original = (validate(payload()) as ValidationResult.Valid).transaction
        val retry = (validate(payload(quantity = "2.000", metadata = """{"batchId": "retry-7"}""")) as ValidationResult.Valid).transaction

        assertThat(retry.fingerprint).isEqualTo(original.fingerprint).hasSize(64)
    }

    @Test
    fun `fingerprint changes when any billable field changes`() {
        val base = ValidTransaction("e", "c", "CODE", Instant.parse("2026-08-15T14:22:31Z"), BigDecimal("2"))
        val variants = listOf(
            base.copy(customerId = "c2"),
            base.copy(transactionCode = "CODE2"),
            base.copy(occurredAt = base.occurredAt.plusSeconds(1)),
            base.copy(quantity = BigDecimal("3")),
        )

        assertThat(variants.map { it.fingerprint }).doesNotContain(base.fingerprint).doesNotHaveDuplicates()
    }

    @Test
    fun `fingerprint cannot be forged by shifting text between fields`() {
        val a = ValidTransaction("e", "ab", "c", Instant.EPOCH, BigDecimal.ONE)
        val b = ValidTransaction("e", "a", "bc", Instant.EPOCH, BigDecimal.ONE)

        assertThat(a.fingerprint).isNotEqualTo(b.fingerprint)
    }
}
