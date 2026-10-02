package com.revenium.rating.pricing

import com.revenium.rating.tenancy.TenantContext
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/pricing-rules")
class PriceRuleController(private val priceRuleService: PriceRuleService) {

    @GetMapping
    fun list(): List<PriceRuleResponse> = priceRuleService.list(TenantContext.requireTenantId()).map(PriceRuleResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreatePriceRuleRequest): PriceRuleResponse =
        PriceRuleResponse.from(
            priceRuleService.create(
                TenantContext.requireTenantId(),
                request.transactionCode!!,
                request.unitPrice!!,
                request.effectiveFrom!!,
                request.effectiveTo,
            )
        )
}

data class CreatePriceRuleRequest(
    @field:NotBlank @field:Size(max = 64) val transactionCode: String?,
    @field:NotNull val unitPrice: BigDecimal?,
    @field:NotNull val effectiveFrom: Instant?,
    val effectiveTo: Instant?,
)

data class PriceRuleResponse(
    val id: UUID,
    val transactionCode: String,
    val unitPrice: BigDecimal,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(rule: PriceRule) = PriceRuleResponse(
            rule.id, rule.transactionCode, rule.unitPrice, rule.effectiveFrom, rule.effectiveTo, rule.createdAt
        )
    }
}
