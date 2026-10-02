package com.revenium.rating.tenancy

/**
 * Holds the tenant the current thread is acting for.
 *
 * Set by [ApiKeyAuthenticationFilter] for HTTP requests, by the rating worker from the claimed row for
 * background work, and copied into @Async tasks by [TenantContextTaskDecorator].
 */
object TenantContext {

    private val currentTenant = ThreadLocal<String>()

    fun currentTenantId(): String? = currentTenant.get()

    fun requireTenantId(): String = currentTenant.get() ?: throw MissingTenantContextException("No tenant context is bound")

    fun <T> runAs(tenantId: String, block: () -> T): T {
        val previous = currentTenant.get()
        currentTenant.set(tenantId)
        try {
            return block()
        } finally {
            if (previous == null) currentTenant.remove() else currentTenant.set(previous)
        }
    }
}

class MissingTenantContextException(message: String) : RuntimeException(message)

class TenantMismatchException(message: String) : RuntimeException(message)
