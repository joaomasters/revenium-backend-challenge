package com.revenium.rating.tenancy

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TenantContextTest {

    @Test
    fun `binds a tenant only for the duration of the block`() {
        assertThat(TenantContext.currentTenantId()).isNull()

        val seen = TenantContext.runAs("tenant-a") { TenantContext.requireTenantId() }

        assertThat(seen).isEqualTo("tenant-a")
        assertThat(TenantContext.currentTenantId()).isNull()
    }

    @Test
    fun `restores the outer tenant after a nested block, even when it throws`() {
        TenantContext.runAs("tenant-a") {
            assertThatThrownBy { TenantContext.runAs("tenant-b") { error("boom") } }.isInstanceOf(IllegalStateException::class.java)
            assertThat(TenantContext.currentTenantId()).isEqualTo("tenant-a")
        }
    }

    @Test
    fun `requiring a tenant outside any context fails`() {
        assertThatThrownBy { TenantContext.requireTenantId() }.isInstanceOf(MissingTenantContextException::class.java)
    }
}

class TenancyPropertiesTest {

    @Test
    fun `maps api keys to tenants`() {
        val properties = TenancyProperties(listOf("tenant-a:key-a", " tenant-b : key-b ", ""))

        assertThat(properties.tenantForApiKey("key-a")).isEqualTo("tenant-a")
        assertThat(properties.tenantForApiKey("key-b")).isEqualTo("tenant-b")
        assertThat(properties.tenantForApiKey("unknown")).isNull()
        assertThat(properties.tenantForApiKey(null)).isNull()
    }

    @Test
    fun `rejects malformed entries at startup`() {
        assertThatThrownBy { TenancyProperties(listOf("no-separator")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TenancyProperties(listOf("tenant-a:")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

/** Target used to prove the aspect against a real Spring AOP proxy. */
@TenantScoped
open class ScopedTarget {
    open fun forTenant(@TenantId tenantId: String, other: String) = "$tenantId/$other"

    open fun noTenantParameter() = "ok"
}

open class MethodScopedTarget {
    @TenantScoped
    open fun guarded() = "guarded"

    open fun unguarded() = "unguarded"
}

class TenantContextAspectTest {

    private inline fun <reified T : Any> proxied(target: T): T =
        AspectJProxyFactory(target).apply { addAspect(TenantContextAspect()) }.getProxy()

    private val scoped = proxied(ScopedTarget())
    private val methodScoped = proxied(MethodScopedTarget())

    @Test
    fun `rejects a call without a tenant context before the method runs`() {
        assertThatThrownBy { scoped.noTenantParameter() }
            .isInstanceOf(MissingTenantContextException::class.java)
            .hasMessageContaining("ScopedTarget.noTenantParameter")
    }

    @Test
    fun `rejects a tenant argument that differs from the bound tenant`() {
        TenantContext.runAs("tenant-a") {
            assertThatThrownBy { scoped.forTenant("tenant-b", "x") }.isInstanceOf(TenantMismatchException::class.java)
        }
    }

    @Test
    fun `allows a call whose tenant argument matches the context`() {
        TenantContext.runAs("tenant-a") {
            assertThat(scoped.forTenant("tenant-a", "tenant-b")).isEqualTo("tenant-a/tenant-b")
        }
    }

    @Test
    fun `method-level annotation guards only that method`() {
        assertThatThrownBy { methodScoped.guarded() }.isInstanceOf(MissingTenantContextException::class.java)
        assertThat(methodScoped.unguarded()).isEqualTo("unguarded")
    }
}

class ApiKeyAuthenticationFilterTest {

    private val filter = ApiKeyAuthenticationFilter(TenancyProperties(listOf("tenant-a:key-a")))

    @Test
    fun `binds the tenant resolved from the api key for the rest of the chain`() {
        val request = MockHttpServletRequest("POST", "/api/v1/transactions").apply { addHeader("X-API-Key", "key-a") }
        var tenantInChain: String? = null
        val chain = FilterChain { _, _ -> tenantInChain = TenantContext.currentTenantId() }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertThat(tenantInChain).isEqualTo("tenant-a")
        assertThat(TenantContext.currentTenantId()).isNull()
    }

    @Test
    fun `rejects unknown or missing keys with 401 and stops the chain`() {
        val chain = mock<FilterChain>()
        for (key in listOf("wrong", null)) {
            val request = MockHttpServletRequest("GET", "/api/v1/reconciliation").apply { key?.let { addHeader("X-API-Key", it) } }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, chain)

            assertThat(response.status).isEqualTo(401)
            assertThat(response.contentAsString).contains("X-API-Key")
        }
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `does not guard non-api paths such as health checks`() {
        val chain = mock<FilterChain>()
        val request = MockHttpServletRequest("GET", "/actuator/health")

        filter.doFilter(request, MockHttpServletResponse(), chain)

        verify(chain).doFilter(any(), any())
    }
}

class TenantContextTaskDecoratorTest {

    private val decorator = TenantContextTaskDecorator()

    @Test
    fun `propagates the submitting thread's tenant into the task`() {
        var seen: String? = null
        val task = TenantContext.runAs("tenant-a") { decorator.decorate { seen = TenantContext.currentTenantId() } }

        val worker = Thread(task).apply { start() }
        worker.join()

        assertThat(seen).isEqualTo("tenant-a")
    }

    @Test
    fun `leaves tasks submitted without a tenant untouched`() {
        val runnable = Runnable { }

        assertThat(decorator.decorate(runnable)).isSameAs(runnable)
    }
}

class TenantTest {

    @Test
    fun `exposes its billing currency`() {
        val tenant = Tenant("tenant-a", "A", "EUR")

        assertThat(tenant.billingCurrency().currencyCode).isEqualTo("EUR")
    }
}
