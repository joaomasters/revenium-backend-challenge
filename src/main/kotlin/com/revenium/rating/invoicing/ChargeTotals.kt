package com.revenium.rating.invoicing

import com.revenium.rating.rating.RatedCharge
import java.math.BigDecimal

data class TransactionCodeTotal(
    val transactionCode: String,
    val transactionCount: Int,
    val quantity: BigDecimal,
    val amount: BigDecimal,
)

object ChargeTotals {

    /**
     * Groups already-rounded charge amounts by transaction code. No further rounding happens here, so each total
     * is exactly the sum of the individual charges a reviewer can trace.
     */
    fun byTransactionCode(charges: List<RatedCharge>): List<TransactionCodeTotal> =
        charges.groupBy { it.transaction.transactionCode }
            .map { (code, codeCharges) ->
                TransactionCodeTotal(
                    transactionCode = code,
                    transactionCount = codeCharges.size,
                    quantity = codeCharges.sumOf { it.transaction.quantity },
                    amount = codeCharges.sumOf { it.amount },
                )
            }
            .sortedBy { it.transactionCode }

    fun total(totals: List<TransactionCodeTotal>): BigDecimal = totals.sumOf { it.amount }
}
