package com.revenium.rating.reconciliation

import com.revenium.rating.common.InvalidRequestException
import com.revenium.rating.ingestion.DuplicateDeliveryRepository
import com.revenium.rating.ingestion.RejectedSubmissionRepository
import com.revenium.rating.ingestion.UsageTransactionRepository
import com.revenium.rating.rating.RatedChargeRepository
import com.revenium.rating.tenancy.TenantId
import com.revenium.rating.tenancy.TenantRepository
import com.revenium.rating.tenancy.TenantScoped
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@TenantScoped
class ReconciliationService(
    private val usageTransactionRepository: UsageTransactionRepository,
    private val ratedChargeRepository: RatedChargeRepository,
    private val duplicateDeliveryRepository: DuplicateDeliveryRepository,
    private val rejectedSubmissionRepository: RejectedSubmissionRepository,
    private val tenantRepository: TenantRepository,
) {

    /**
     * REPEATABLE READ: the report runs several queries, and all of them must see the same snapshot or the counts
     * and amounts would not add up while workers and invoicing commit concurrently.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun report(@TenantId tenantId: String, customerId: String, from: Instant, to: Instant): ReconciliationReport {
        if (!from.isBefore(to)) throw InvalidRequestException("'from' must be before 'to'")

        val transactions = usageTransactionRepository.findForCustomer(tenantId, customerId, from, to)
        val charges = ratedChargeRepository.findForCustomer(tenantId, customerId, from, to)
        val duplicates = if (transactions.isEmpty()) {
            emptyList()
        } else {
            duplicateDeliveryRepository.findByTenantIdAndTransactionIdIn(tenantId, transactions.map { it.id })
        }
        val rejections = rejectedSubmissionRepository.findForCustomer(tenantId, customerId, from, to)

        return ReconciliationReport.assemble(
            customerId = customerId,
            from = from,
            to = to,
            currency = tenantRepository.findById(tenantId).orElseThrow().currency,
            transactions = transactions,
            charges = charges,
            duplicates = duplicates,
            rejections = rejections,
        )
    }
}
