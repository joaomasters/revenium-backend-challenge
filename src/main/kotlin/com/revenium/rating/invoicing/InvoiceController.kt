package com.revenium.rating.invoicing

import com.revenium.rating.common.Money
import com.revenium.rating.tenancy.TenantContext
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceController(
    private val invoiceService: InvoiceService,
    private val invoiceSummaryService: InvoiceSummaryService,
) {

    /** 201 when the invoice is issued by this call, 200 when the period was already invoiced. */
    @PostMapping
    fun issue(@Valid @RequestBody request: IssueInvoiceRequest): ResponseEntity<InvoiceResponse> {
        val issued = invoiceService.issue(TenantContext.requireTenantId(), request.customerId!!, request.billingPeriod!!)
        return ResponseEntity.status(if (issued.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(InvoiceResponse.from(issued.details))
    }

    @GetMapping("/{invoiceId}")
    fun get(@PathVariable invoiceId: UUID): InvoiceResponse =
        InvoiceResponse.from(invoiceService.get(TenantContext.requireTenantId(), invoiceId))

    @GetMapping
    fun list(@RequestParam customerId: String): List<InvoiceHeader> =
        invoiceService.list(TenantContext.requireTenantId(), customerId).map(InvoiceHeader::from)

    @GetMapping("/summary")
    fun summary(
        @RequestParam customerId: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): InvoiceSummary = invoiceSummaryService.summarize(TenantContext.requireTenantId(), customerId, from, to)
}

data class IssueInvoiceRequest(
    @field:NotBlank val customerId: String?,
    @field:NotNull val billingPeriod: YearMonth?,
)

data class InvoiceHeader(
    val invoiceId: UUID,
    val customerId: String,
    val billingPeriod: YearMonth,
    val currency: String,
    val transactionCount: Int,
    val totalAmount: BigDecimal,
    val issuedAt: Instant,
) {
    companion object {
        fun from(invoice: Invoice) = InvoiceHeader(
            invoice.id,
            invoice.customerId,
            YearMonth.from(invoice.billingPeriod),
            invoice.currency,
            invoice.transactionCount,
            Money.inMinorUnits(invoice.totalAmount, invoice.currency),
            invoice.issuedAt,
        )
    }
}

data class InvoiceChargeResponse(
    val eventId: String,
    val transactionCode: String,
    val occurredAt: Instant,
    val quantity: BigDecimal,
    val priceRuleId: UUID,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val lateArrival: Boolean,
)

data class InvoiceResponse(
    val invoice: InvoiceHeader,
    val lines: List<TransactionCodeTotal>,
    val charges: List<InvoiceChargeResponse>,
) {
    companion object {
        fun from(details: InvoiceDetails) = InvoiceResponse(
            invoice = InvoiceHeader.from(details.invoice),
            lines = details.invoice.lines.map {
                TransactionCodeTotal(it.transactionCode, it.transactionCount, it.quantity, Money.inMinorUnits(it.amount, details.invoice.currency))
            },
            charges = details.charges.map {
                InvoiceChargeResponse(
                    eventId = it.transaction.eventId,
                    transactionCode = it.transaction.transactionCode,
                    occurredAt = it.transaction.occurredAt,
                    quantity = it.transaction.quantity,
                    priceRuleId = it.priceRuleId,
                    unitPrice = it.unitPrice,
                    amount = Money.inMinorUnits(it.amount, it.currency),
                    lateArrival = it.lateArrival,
                )
            },
        )
    }
}
