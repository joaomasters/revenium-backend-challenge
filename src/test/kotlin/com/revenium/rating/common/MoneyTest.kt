package com.revenium.rating.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {

    @Test
    fun `presents stored amounts at the currency minor-unit scale`() {
        assertThat(Money.inMinorUnits(BigDecimal("27.5000"), "USD")).isEqualTo(BigDecimal("27.50"))
        assertThat(Money.inMinorUnits(BigDecimal.ZERO, "EUR")).isEqualTo(BigDecimal("0.00"))
        assertThat(Money.inMinorUnits(BigDecimal("452.0000"), "JPY")).isEqualTo(BigDecimal("452"))
    }

    @Test
    fun `never rounds silently`() {
        assertThatThrownBy { Money.inMinorUnits(BigDecimal("27.505"), "USD") }.isInstanceOf(ArithmeticException::class.java)
    }
}
