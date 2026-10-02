package com.revenium.rating.tenancy

/**
 * Marks a service boundary that must only run inside a tenant context. See [TenantContextAspect].
 * Can be placed on a class (all public methods) or on individual methods.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TenantScoped

/**
 * Marks the method parameter carrying the tenant the call operates on. [TenantContextAspect] rejects the call
 * when it differs from the bound tenant context.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class TenantId
