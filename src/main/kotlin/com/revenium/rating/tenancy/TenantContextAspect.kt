package com.revenium.rating.tenancy

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Fails fast when a [TenantScoped] boundary is entered without a tenant context, or with a [TenantId]
 * argument that differs from the bound tenant.
 *
 * Ordering: it must run before the transaction interceptor (which defaults to LOWEST_PRECEDENCE), so a rejected
 * call never opens a transaction or borrows a connection; and after Spring's ExposeInvocationInterceptor
 * (HIGHEST_PRECEDENCE + 1), which AspectJ-style advice needs to access the join point.
 */
@Aspect
@Component
@Order(TenantContextAspect.ORDER)
class TenantContextAspect {

    @Before(
        "@within(com.revenium.rating.tenancy.TenantScoped) || @annotation(com.revenium.rating.tenancy.TenantScoped)"
    )
    fun verifyTenantContext(joinPoint: JoinPoint) {
        val method = (joinPoint.signature as MethodSignature).method
        val boundTenant = TenantContext.currentTenantId()
            ?: throw MissingTenantContextException("${method.declaringClass.simpleName}.${method.name} requires a tenant context")

        method.parameterAnnotations.forEachIndexed { index, annotations ->
            if (annotations.any { it is TenantId }) {
                val requestedTenant = joinPoint.args[index]
                if (requestedTenant != boundTenant) {
                    throw TenantMismatchException(
                        "${method.declaringClass.simpleName}.${method.name} called for tenant '$requestedTenant' " +
                            "while the bound tenant is '$boundTenant'"
                    )
                }
            }
        }
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 10
    }
}
