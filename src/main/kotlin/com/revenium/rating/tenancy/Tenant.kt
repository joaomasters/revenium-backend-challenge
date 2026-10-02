package com.revenium.rating.tenancy

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Currency

@Entity
@Table(name = "tenant")
class Tenant(
    @Id
    val id: String,
    val name: String,
    val currency: String,
) {
    fun billingCurrency(): Currency = Currency.getInstance(currency)
}

interface TenantRepository : JpaRepository<Tenant, String>
