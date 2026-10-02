package com.revenium.rating.web

import com.revenium.rating.Fixtures
import com.revenium.rating.common.ConflictException
import com.revenium.rating.common.NotFoundException
import com.revenium.rating.common.PayloadTooLargeException
import com.revenium.rating.ingestion.IngestionResult
import com.revenium.rating.ingestion.IngestionService
import com.revenium.rating.ingestion.TransactionController
import com.revenium.rating.ingestion.TransactionStatus
import com.revenium.rating.invoicing.Invoice
import com.revenium.rating.invoicing.InvoiceController
import com.revenium.rating.invoicing.InvoiceDetails
import com.revenium.rating.invoicing.InvoiceLine
import com.revenium.rating.invoicing.InvoiceService
import com.revenium.rating.invoicing.InvoiceSummary
import com.revenium.rating.invoicing.InvoiceSummaryService
import com.revenium.rating.invoicing.IssuedInvoice
import com.revenium.rating.invoicing.TransactionCodeTotal
import com.revenium.rating.pricing.PriceRuleController
import com.revenium.rating.pricing.PriceRuleService
import com.revenium.rating.rating.RatingService
import com.revenium.rating.reconciliation.ReconciliationController
import com.revenium.rating.reconciliation.ReconciliationReport
import com.revenium.rating.reconciliation.ReconciliationService
import com.revenium.rating.tenancy.MissingTenantContextException
import com.revenium.rating.tenancy.TenancyProperties
import com.revenium.rating.tenancy.TenantContext
import com.revenium.rating.tenancy.TenantMismatchException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private const val API_KEY = "test-key-a"
private const val MAX_PAYLOAD_BYTES = 256

/**
 * Web slices: HTTP contract, status codes, the API-key filter and error mapping, with services mocked.
 * No database or Docker is needed, so these run in the unit-test suite.
 */
@WebMvcTest(TransactionController::class)
@TestPropertySource(properties = ["app.tenancy.api-keys=tenant-a:$API_KEY", "app.ingestion.max-payload-bytes=$MAX_PAYLOAD_BYTES"])
@EnableConfigurationProperties(TenancyProperties::class)
class TransactionControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var ingestionService: IngestionService

    @MockitoBean
    private lateinit var ratingService: RatingService

    private val id = UUID.randomUUID()

    private fun postTransaction(body: String = "{}") = mockMvc.post("/api/v1/transactions") {
        header("X-API-Key", API_KEY)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `passes the raw body and the authenticated tenant to ingestion and answers 202`() {
        whenever(ingestionService.ingest("tenant-a", """{"eventId":"e-1"}""")).thenReturn(IngestionResult.Accepted(id, "e-1"))

        postTransaction("""{"eventId":"e-1"}""").andExpect {
            status { isAccepted() }
            jsonPath("$.outcome") { value("ACCEPTED") }
            jsonPath("$.transactionId") { value(id.toString()) }
            jsonPath("$.processingStatus") { value("PENDING") }
            jsonPath("$.errors") { doesNotExist() }
        }
    }

    @Test
    fun `accepts a body of exactly the configured maximum size`() {
        val body = "{\"pad\":\"" + "x".repeat(MAX_PAYLOAD_BYTES - 10) + "\"}"
        check(body.toByteArray().size == MAX_PAYLOAD_BYTES)
        whenever(ingestionService.ingest("tenant-a", body)).thenReturn(IngestionResult.Rejected(id, listOf("eventId: is required"), false))

        postTransaction(body).andExpect { status { isBadRequest() } }

        verify(ingestionService).ingest("tenant-a", body)
    }

    @Test
    fun `answers 413 for a body over the limit and never reaches ingestion, so nothing is stored`() {
        val body = "{\"pad\":\"" + "x".repeat(MAX_PAYLOAD_BYTES - 9) + "\"}"

        postTransaction(body).andExpect {
            status { isContentTooLarge() }
            jsonPath("$.detail") { value("Request body exceeds $MAX_PAYLOAD_BYTES bytes") }
        }

        verifyNoInteractions(ingestionService)
    }

    @Test
    fun `enforces the limit by bounded reading when the length is unknown, as with chunked uploads`() {
        val chunked = object : MockHttpServletRequest("POST", "/api/v1/transactions") {
            override fun getContentLengthLong() = -1L
            override fun getContentLength() = -1
        }.apply { setContent(ByteArray(MAX_PAYLOAD_BYTES + 1) { 'x'.code.toByte() }) }
        val controller = TransactionController(ingestionService, ratingService, MAX_PAYLOAD_BYTES)

        assertThatThrownBy { TenantContext.runAs("tenant-a") { controller.ingest(chunked) } }
            .isInstanceOf(PayloadTooLargeException::class.java)
        verifyNoInteractions(ingestionService)
    }

    @Test
    fun `answers 200 for an identical re-delivery`() {
        whenever(ingestionService.ingest(any(), any())).thenReturn(IngestionResult.Duplicate(id, "e-1", TransactionStatus.RATED))

        postTransaction().andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("DUPLICATE") }
            jsonPath("$.processingStatus") { value("RATED") }
        }
    }

    @Test
    fun `answers 409 when an eventId is re-used with different content`() {
        whenever(ingestionService.ingest(any(), any())).thenReturn(IngestionResult.DuplicateConflict(id, "e-1"))

        postTransaction().andExpect {
            status { isConflict() }
            jsonPath("$.outcome") { value("DUPLICATE_CONFLICT") }
        }
    }

    @Test
    fun `answers 400 with all validation errors`() {
        whenever(ingestionService.ingest(any(), any())).thenReturn(IngestionResult.Rejected(id, listOf("a", "b"), tenantMismatch = false))

        postTransaction().andExpect {
            status { isBadRequest() }
            jsonPath("$.outcome") { value("REJECTED") }
            jsonPath("$.rejectionId") { value(id.toString()) }
            jsonPath("$.errors.length()") { value(2) }
        }
    }

    @Test
    fun `answers 403 when the payload names another tenant`() {
        whenever(ingestionService.ingest(any(), any())).thenReturn(IngestionResult.Rejected(id, listOf("tenantId"), tenantMismatch = true))

        postTransaction().andExpect { status { isForbidden() } }
    }

    @Test
    fun `answers 401 without a valid api key and never reaches the service`() {
        mockMvc.post("/api/v1/transactions") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }

        verifyNoInteractions(ingestionService)
    }

    @Test
    fun `requeues a transaction`() {
        whenever(ratingService.requeue("tenant-a", "e-1")).thenReturn(Fixtures.usageTransaction(eventId = "e-1"))

        mockMvc.post("/api/v1/transactions/e-1/reprocess") { header("X-API-Key", API_KEY) }.andExpect {
            status { isAccepted() }
            jsonPath("$.outcome") { value("REQUEUED") }
            jsonPath("$.processingStatus") { value("PENDING") }
        }
    }

    @Test
    fun `maps domain exceptions to problem details`() {
        whenever(ratingService.requeue(any(), eq("rated"))).thenThrow(ConflictException("already rated"))
        whenever(ratingService.requeue(any(), eq("missing"))).thenThrow(NotFoundException("missing"))
        whenever(ratingService.requeue(any(), eq("other-tenant"))).thenThrow(TenantMismatchException("mismatch"))
        whenever(ratingService.requeue(any(), eq("no-context"))).thenThrow(MissingTenantContextException("none"))

        mockMvc.post("/api/v1/transactions/rated/reprocess") { header("X-API-Key", API_KEY) }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("already rated") }
        }
        mockMvc.post("/api/v1/transactions/missing/reprocess") { header("X-API-Key", API_KEY) }.andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/transactions/other-tenant/reprocess") { header("X-API-Key", API_KEY) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/transactions/no-context/reprocess") { header("X-API-Key", API_KEY) }.andExpect {
            status { isInternalServerError() }
        }
    }
}

@WebMvcTest(InvoiceController::class)
@TestPropertySource(properties = ["app.tenancy.api-keys=tenant-a:$API_KEY"])
@EnableConfigurationProperties(TenancyProperties::class)
class InvoiceControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var invoiceService: InvoiceService

    @MockitoBean
    private lateinit var invoiceSummaryService: InvoiceSummaryService

    private val invoice = Invoice(
        id = UUID.randomUUID(),
        tenantId = "tenant-a",
        customerId = Fixtures.CUSTOMER,
        billingPeriod = LocalDate.of(2026, 8, 1),
        currency = "USD",
        transactionCount = 1,
        // Values as read back from NUMERIC(19,4) columns.
        totalAmount = BigDecimal("27.5000"),
        issuedAt = Fixtures.NOW,
        lines = listOf(InvoiceLine(UUID.randomUUID(), "tenant-a", "VEHICLE_REGISTRATION", 1, BigDecimal("2.000000"), BigDecimal("27.5000"))),
    )
    private val details = InvoiceDetails(invoice, listOf(Fixtures.charge(amount = "27.5000", invoiceId = invoice.id)))

    private fun issue() = mockMvc.post("/api/v1/invoices") {
        header("X-API-Key", API_KEY)
        contentType = MediaType.APPLICATION_JSON
        content = """{"customerId": "customer-42", "billingPeriod": "2026-08"}"""
    }

    @Test
    fun `answers 201 with lines and traceable charges when an invoice is issued`() {
        whenever(invoiceService.issue("tenant-a", Fixtures.CUSTOMER, YearMonth.of(2026, 8))).thenReturn(IssuedInvoice(details, created = true))

        issue().andExpect {
            status { isCreated() }
            jsonPath("$.invoice.billingPeriod") { value("2026-08") }
            jsonPath("$.invoice.totalAmount") { value(27.50) }
            jsonPath("$.lines[0].transactionCode") { value("VEHICLE_REGISTRATION") }
            jsonPath("$.charges[0].priceRuleId") { value(details.charges[0].priceRuleId.toString()) }
            jsonPath("$.charges[0].eventId") { value(details.charges[0].transaction.eventId) }
            content { string(org.hamcrest.Matchers.containsString("\"totalAmount\":27.50,")) }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("27.5000"))) }
        }
    }

    @Test
    fun `answers 200 when the period was already invoiced`() {
        whenever(invoiceService.issue(any(), any(), any())).thenReturn(IssuedInvoice(details, created = false))

        issue().andExpect { status { isOk() } }
    }

    @Test
    fun `validates the issue request`() {
        mockMvc.post("/api/v1/invoices") {
            header("X-API-Key", API_KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId": " "}"""
        }.andExpect { status { isBadRequest() } }

        mockMvc.post("/api/v1/invoices") {
            header("X-API-Key", API_KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId": "c", "billingPeriod": "August"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `gets and lists invoices`() {
        whenever(invoiceService.get("tenant-a", invoice.id)).thenReturn(details)
        whenever(invoiceService.list("tenant-a", Fixtures.CUSTOMER)).thenReturn(listOf(invoice))

        mockMvc.get("/api/v1/invoices/${invoice.id}") { header("X-API-Key", API_KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.invoice.invoiceId") { value(invoice.id.toString()) }
        }
        mockMvc.get("/api/v1/invoices?customerId=${Fixtures.CUSTOMER}") { header("X-API-Key", API_KEY) }.andExpect {
            status { isOk() }
            jsonPath("$[0].transactionCount") { value(1) }
        }
    }

    @Test
    fun `returns the summary for a time range`() {
        val from = Instant.parse("2026-08-01T00:00:00Z")
        val to = Instant.parse("2026-09-01T00:00:00Z")
        whenever(invoiceSummaryService.summarize("tenant-a", Fixtures.CUSTOMER, from, to)).thenReturn(
            InvoiceSummary(
                Fixtures.CUSTOMER, from, to, "USD",
                listOf(TransactionCodeTotal("VEHICLE_REGISTRATION", 1, BigDecimal("2"), BigDecimal("27.50"))),
                1, BigDecimal("27.50"), 0,
            )
        )

        mockMvc.get("/api/v1/invoices/summary?customerId=${Fixtures.CUSTOMER}&from=$from&to=$to") {
            header("X-API-Key", API_KEY)
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalAmount") { value(27.50) }
            jsonPath("$.lines[0].quantity") { value(2) }
        }
    }

    @Test
    fun `rejects malformed query parameters`() {
        mockMvc.get("/api/v1/invoices/summary?customerId=c&from=yesterday&to=today") { header("X-API-Key", API_KEY) }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/invoices/summary?customerId=c") { header("X-API-Key", API_KEY) }
            .andExpect { status { isBadRequest() } }
    }
}

@WebMvcTest(ReconciliationController::class, PriceRuleController::class)
@TestPropertySource(properties = ["app.tenancy.api-keys=tenant-a:$API_KEY"])
@EnableConfigurationProperties(TenancyProperties::class)
class ReconciliationAndPricingControllerTest(@Autowired private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var reconciliationService: ReconciliationService

    @MockitoBean
    private lateinit var priceRuleService: PriceRuleService

    @Test
    fun `returns the reconciliation report for the authenticated tenant`() {
        val from = Instant.parse("2026-08-01T00:00:00Z")
        val to = Instant.parse("2026-09-01T00:00:00Z")
        whenever(reconciliationService.report("tenant-a", Fixtures.CUSTOMER, from, to)).thenReturn(
            ReconciliationReport.assemble(Fixtures.CUSTOMER, from, to, "USD", emptyList(), emptyList(), emptyList(), emptyList())
        )

        mockMvc.get("/api/v1/reconciliation?customerId=${Fixtures.CUSTOMER}&from=$from&to=$to") {
            header("X-API-Key", API_KEY)
        }.andExpect {
            status { isOk() }
            jsonPath("$.counts.accepted") { value(0) }
        }
    }

    @Test
    fun `lists and creates price rules`() {
        val rule = Fixtures.priceRule(transactionCode = "INSPECTION", unitPrice = "4.50", from = "2026-11-01T00:00:00Z")
        whenever(priceRuleService.list("tenant-a")).thenReturn(listOf(rule))
        whenever(priceRuleService.create(eq("tenant-a"), eq("INSPECTION"), any(), any(), anyOrNull())).thenReturn(rule)

        mockMvc.get("/api/v1/pricing-rules") { header("X-API-Key", API_KEY) }.andExpect {
            status { isOk() }
            jsonPath("$[0].transactionCode") { value("INSPECTION") }
        }
        mockMvc.post("/api/v1/pricing-rules") {
            header("X-API-Key", API_KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"transactionCode": "INSPECTION", "unitPrice": 4.50, "effectiveFrom": "2026-11-01T00:00:00Z"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(rule.id.toString()) }
        }
        verify(priceRuleService).create("tenant-a", "INSPECTION", BigDecimal("4.50"), Instant.parse("2026-11-01T00:00:00Z"), null)
    }

    @Test
    fun `validates price rule requests`() {
        mockMvc.post("/api/v1/pricing-rules") {
            header("X-API-Key", API_KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"transactionCode": "", "effectiveFrom": "2026-11-01T00:00:00Z"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { exists() }
        }
    }
}
