package com.revenium.rating.reconciliation

import com.revenium.rating.tenancy.TenantContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/reconciliation")
class ReconciliationController(private val reconciliationService: ReconciliationService) {

    @GetMapping
    fun report(
        @RequestParam customerId: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ReconciliationReport = reconciliationService.report(TenantContext.requireTenantId(), customerId, from, to)
}
