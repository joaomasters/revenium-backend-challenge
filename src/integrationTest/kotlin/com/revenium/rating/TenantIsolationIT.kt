package com.revenium.rating

import com.revenium.rating.rating.RatingService
import com.revenium.rating.reconciliation.ReconciliationService
import com.revenium.rating.tenancy.MissingTenantContextException
import com.revenium.rating.tenancy.TenantContext
import com.revenium.rating.tenancy.TenantMismatchException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

class TenantIsolationIT : IntegrationTest() {

    @Autowired
    private lateinit var ratingService: RatingService

    @Autowired
    private lateinit var reconciliationService: ReconciliationService

    private val from = "2026-08-01T00:00:00Z"
    private val to = "2026-09-01T00:00:00Z"

    @Test
    fun `a tenant cannot submit usage on behalf of another tenant`() {
        val customer = newCustomer()

        postTransaction(transactionJson(customerId = customer, tenantId = "tenant-b"), apiKey = TENANT_A_KEY).andExpect {
            status { isForbidden() }
            jsonPath("$.outcome") { value("REJECTED") }
        }

        assertThat(countRows("SELECT count(*) FROM usage_transaction WHERE customer_id = ?", customer)).isZero()
        assertThat(countRows("SELECT count(*) FROM rejected_submission WHERE customer_id = ? AND tenant_id = 'tenant-a'", customer)).isEqualTo(1)
    }

    @Test
    fun `the same customer and event ids in two tenants are rated, reported and invoiced independently`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer), TENANT_A_KEY).andExpect { status { isAccepted() } }
        postTransaction(transactionJson(eventId = eventId, customerId = customer, tenantId = "tenant-b"), TENANT_B_KEY)
            .andExpect { status { isAccepted() } }
        rateEverythingDue()

        // Each tenant is priced with its own rule and currency.
        summary(customer, from, to, TENANT_A_KEY).andExpect {
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.totalAmount") { value(27.50) }
        }
        summary(customer, from, to, TENANT_B_KEY).andExpect {
            jsonPath("$.currency") { value("EUR") }
            jsonPath("$.totalAmount") { value(22.00) }
        }
        reconciliation(customer, from, to, TENANT_B_KEY).andExpect {
            jsonPath("$.counts.accepted") { value(1) }
            jsonPath("$.transactions[0].priceRuleId") { value("00000000-0000-0000-0000-00000000b001") }
        }
        issueInvoice(customer, "2026-08", TENANT_B_KEY).andExpect {
            status { isCreated() }
            jsonPath("$.invoice.transactionCount") { value(1) }
            jsonPath("$.invoice.totalAmount") { value(22.00) }
        }
        // Tenant B's invoice did not consume tenant A's charge.
        issueInvoice(customer, "2026-08", TENANT_A_KEY).andExpect {
            status { isCreated() }
            jsonPath("$.invoice.totalAmount") { value(27.50) }
        }
    }

    @Test
    fun `another tenant's data is invisible through every endpoint`() {
        val customer = newCustomer()
        val eventId = newEventId()
        postTransaction(transactionJson(eventId = eventId, customerId = customer, transactionCode = "UNPRICED_FOR_B"), TENANT_A_KEY)
            .andExpect { status { isAccepted() } }
        ingest(customer, "2026-08-10T10:00:00Z" to "1")
        rateEverythingDue()
        val invoiceId = issueInvoice(customer, "2026-08", TENANT_A_KEY).andReturn().response.contentAsString
            .let { Regex("\"invoiceId\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

        reconciliation(customer, from, to, TENANT_B_KEY).andExpect {
            jsonPath("$.counts.received") { value(0) }
            jsonPath("$.transactions.length()") { value(0) }
        }
        summary(customer, from, to, TENANT_B_KEY).andExpect { jsonPath("$.transactionCount") { value(0) } }
        mockMvc.get("/api/v1/invoices/$invoiceId") { header("X-API-Key", TENANT_B_KEY) }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/invoices?customerId=$customer") { header("X-API-Key", TENANT_B_KEY) }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
        mockMvc.post("/api/v1/transactions/$eventId/reprocess") { header("X-API-Key", TENANT_B_KEY) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `the aspect rejects a service call whose tenant argument differs from the bound context`() {
        assertThatThrownBy {
            TenantContext.runAs("tenant-b") {
                reconciliationService.report("tenant-a", newCustomer(), Instant.parse(from), Instant.parse(to))
            }
        }.isInstanceOf(TenantMismatchException::class.java)
    }

    @Test
    fun `background-style calls without any tenant context are refused`() {
        assertThatThrownBy { ratingService.rate("tenant-a", UUID.randomUUID()) }.isInstanceOf(MissingTenantContextException::class.java)
    }
}
