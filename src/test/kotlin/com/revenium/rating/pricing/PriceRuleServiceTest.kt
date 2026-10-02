package com.revenium.rating.pricing

import com.revenium.rating.Fixtures
import com.revenium.rating.common.ConflictException
import com.revenium.rating.common.InvalidRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant

class PriceRuleServiceTest {

    private val repository = mock<PriceRuleRepository>()
    private val service = PriceRuleService(repository, Fixtures.CLOCK)
    private val from = Instant.parse("2026-11-01T00:00:00Z")

    @Test
    fun `creates an immutable rule stamped with the creation time`() {
        whenever(repository.persistAndFlush(any())).thenAnswer { it.arguments[0] }

        val rule = service.create(Fixtures.TENANT, "INSPECTION", BigDecimal("4.50"), from, null)

        val saved = argumentCaptor<PriceRule>().apply { verify(repository).persistAndFlush(capture()) }.firstValue
        assertThat(saved).isSameAs(rule)
        assertThat(rule.tenantId).isEqualTo(Fixtures.TENANT)
        assertThat(rule.createdAt).isEqualTo(Fixtures.NOW)
        assertThat(rule.effectiveTo).isNull()
    }

    @Test
    fun `rejects negative prices`() {
        assertThatThrownBy { service.create(Fixtures.TENANT, "INSPECTION", BigDecimal("-0.01"), from, null) }
            .isInstanceOf(InvalidRequestException::class.java)
        verify(repository, never()).persistAndFlush(any())
    }

    @Test
    fun `rejects prices with more precision than the column`() {
        assertThatThrownBy { service.create(Fixtures.TENANT, "INSPECTION", BigDecimal("0.0000001"), from, null) }
            .isInstanceOf(InvalidRequestException::class.java)
    }

    @Test
    fun `rejects an end that is not after the start`() {
        assertThatThrownBy { service.create(Fixtures.TENANT, "INSPECTION", BigDecimal.ONE, from, from) }
            .isInstanceOf(InvalidRequestException::class.java)
    }

    @Test
    fun `translates the overlap exclusion constraint into a conflict`() {
        whenever(repository.persistAndFlush(any()))
            .thenThrow(DataIntegrityViolationException("overlap", SQLException("conflicting key", "23P01")))

        assertThatThrownBy { service.create(Fixtures.TENANT, "INSPECTION", BigDecimal.ONE, from, null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `propagates other integrity violations unchanged`() {
        val fkViolation = DataIntegrityViolationException("fk", SQLException("unknown tenant", "23503"))
        whenever(repository.persistAndFlush(any())).thenThrow(fkViolation)

        assertThatThrownBy { service.create(Fixtures.TENANT, "INSPECTION", BigDecimal.ONE, from, null) }.isSameAs(fkViolation)
    }

    @Test
    fun `lists only the requested tenant's rules`() {
        val rules = listOf(Fixtures.priceRule())
        whenever(repository.findByTenantIdOrderByTransactionCodeAscEffectiveFromAsc(Fixtures.TENANT)).thenReturn(rules)

        assertThat(service.list(Fixtures.TENANT)).isEqualTo(rules)
    }
}
