package com.revenium.rating.pricing

import com.revenium.rating.common.InsertOnlyRepository
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PriceRuleRepository : JpaRepository<PriceRule, UUID>, InsertOnlyRepository<PriceRule> {

    fun findByTenantIdAndTransactionCodeOrderByEffectiveFrom(tenantId: String, transactionCode: String): List<PriceRule>

    fun findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(tenantId: String): List<PriceRule>
}
