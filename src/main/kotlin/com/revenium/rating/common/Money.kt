package com.revenium.rating.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

object Money {

    /**
     * Presents an amount at the currency's minor-unit scale (27.5000 -> 27.50 USD). Stored amounts are already
     * rounded to that scale when rated, so this never rounds; UNNECESSARY makes any violation fail loudly.
     */
    fun inMinorUnits(amount: BigDecimal, currencyCode: String): BigDecimal =
        amount.setScale(Currency.getInstance(currencyCode).defaultFractionDigits, RoundingMode.UNNECESSARY)
}
