package com.revenium.rating.pricing

import com.revenium.rating.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PriceRuleTest {

    private val closedRule = Fixtures.priceRule(from = "2026-01-01T00:00:00Z", to = "2026-08-01T00:00:00Z")
    private val openEndedRule = Fixtures.priceRule(from = "2026-08-01T00:00:00Z", to = null)

    @Test
    fun `effective start is inclusive`() {
        assertThat(closedRule.isEffectiveAt(Instant.parse("2026-01-01T00:00:00Z"))).isTrue()
    }

    @Test
    fun `instant before the start is not covered`() {
        assertThat(closedRule.isEffectiveAt(Instant.parse("2025-12-31T23:59:59.999999Z"))).isFalse()
    }

    @Test
    fun `effective end is exclusive`() {
        assertThat(closedRule.isEffectiveAt(Instant.parse("2026-07-31T23:59:59.999999Z"))).isTrue()
        assertThat(closedRule.isEffectiveAt(Instant.parse("2026-08-01T00:00:00Z"))).isFalse()
    }

    @Test
    fun `adjacent rules hand over at exactly one instant`() {
        val boundary = Instant.parse("2026-08-01T00:00:00Z")
        assertThat(listOf(closedRule, openEndedRule).filter { it.isEffectiveAt(boundary) }).containsExactly(openEndedRule)
    }

    @Test
    fun `rule without an end stays effective indefinitely`() {
        assertThat(openEndedRule.isEffectiveAt(Instant.parse("2100-01-01T00:00:00Z"))).isTrue()
    }
}
