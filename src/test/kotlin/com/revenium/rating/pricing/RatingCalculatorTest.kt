package com.revenium.rating.pricing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.util.Currency

class RatingCalculatorTest {

    private val usd = Currency.getInstance("USD")

    @Test
    fun `multiplies quantity by unit price and scales to the currency minor unit`() {
        assertThat(RatingCalculator.amount(BigDecimal("2"), BigDecimal("13.75"), usd)).isEqualTo(BigDecimal("27.50"))
    }

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource(
        "1, 0.125, 0.12", // half-even: tie rounds to the even neighbour
        "1, 0.135, 0.14",
        "1, 0.1251, 0.13", // not a tie: rounds up
        "3, 0.333333, 1.00",
        "0.5, 0.01, 0.00",
        "1.5, 0.01, 0.02",
    )
    fun `rounds half-even exactly once per transaction`(quantity: String, unitPrice: String, expected: String) {
        assertThat(RatingCalculator.amount(BigDecimal(quantity), BigDecimal(unitPrice), usd)).isEqualTo(BigDecimal(expected))
    }

    @Test
    fun `uses zero decimals for currencies without minor units`() {
        assertThat(RatingCalculator.amount(BigDecimal("3"), BigDecimal("150.5"), Currency.getInstance("JPY")))
            .isEqualTo(BigDecimal("452"))
    }

    @Test
    fun `keeps exact decimal arithmetic for large quantities`() {
        assertThat(RatingCalculator.amount(BigDecimal("9999999999999.999999"), BigDecimal("0.000001"), usd))
            .isEqualTo(BigDecimal("10000000.00"))
    }
}
