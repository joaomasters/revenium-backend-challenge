package com.revenium.rating.tenancy

import org.springframework.core.task.TaskDecorator
import org.springframework.stereotype.Component

/**
 * Copies the submitting thread's tenant context into @Async tasks. Spring Boot applies a single TaskDecorator
 * bean to its auto-configured task executor.
 */
@Component
class TenantContextTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val tenantId = TenantContext.currentTenantId() ?: return runnable
        return Runnable { TenantContext.runAs(tenantId) { runnable.run() } }
    }
}
