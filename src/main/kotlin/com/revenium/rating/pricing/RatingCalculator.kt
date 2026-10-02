package com.revenium.rating.pricing

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

object RatingCalculator {

    /**
     * Each transaction is rounded once, to the currency's minor unit, using banker's rounding.
     * Invoice totals are sums of these rounded amounts, so every total reconciles exactly to its lines.
     */
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    fun amount(quantity: BigDecimal, unitPrice: BigDecimal, currency: Currency): BigDecimal =
        quantity.multiply(unitPrice).setScale(currency.defaultFractionDigits, ROUNDING)
}
