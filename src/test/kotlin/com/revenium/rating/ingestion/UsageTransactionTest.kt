package com.revenium.rating.ingestion

import com.revenium.rating.Fixtures
import com.revenium.rating.common.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration

class UsageTransactionTest {

    private val now = Fixtures.NOW
    private val backoff = Duration.ofSeconds(30)

    @Test
    fun `starts pending and immediately due`() {
        val transaction = Fixtures.usageTransaction()

        assertThat(transaction.status).isEqualTo(TransactionStatus.PENDING)
        assertThat(transaction.attempts).isZero()
        assertThat(transaction.nextAttemptAt).isEqualTo(transaction.receivedAt)
    }

    @Test
    fun `marks rated`() {
        val transaction = Fixtures.usageTransaction()

        transaction.markRated(now)

        assertThat(transaction.status).isEqualTo(TransactionStatus.RATED)
        assertThat(transaction.processedAt).isEqualTo(now)
    }

    @Test
    fun `marks unrated with the reason`() {
        val transaction = Fixtures.usageTransaction()

        transaction.markUnrated("no rule", now)

        assertThat(transaction.status).isEqualTo(TransactionStatus.UNRATED)
        assertThat(transaction.statusReason).isEqualTo("no rule")
    }

    @Test
    fun `cannot be rated twice`() {
        val transaction = Fixtures.usageTransaction()
        transaction.markRated(now)

        assertThatThrownBy { transaction.markRated(now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `failed attempts back off linearly until the attempt budget is used`() {
        val transaction = Fixtures.usageTransaction()

        assertThat(transaction.recordFailedAttempt("boom", now, maxAttempts = 3, retryBackoff = backoff)).isFalse()
        assertThat(transaction.nextAttemptAt).isEqualTo(now.plusSeconds(30))
        assertThat(transaction.recordFailedAttempt("boom", now, maxAttempts = 3, retryBackoff = backoff)).isFalse()
        assertThat(transaction.nextAttemptAt).isEqualTo(now.plusSeconds(60))
        assertThat(transaction.status).isEqualTo(TransactionStatus.PENDING)

        assertThat(transaction.recordFailedAttempt("boom", now, maxAttempts = 3, retryBackoff = backoff)).isTrue()
        assertThat(transaction.status).isEqualTo(TransactionStatus.FAILED)
        assertThat(transaction.attempts).isEqualTo(3)
        assertThat(transaction.statusReason).isEqualTo("boom")
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus::class, names = ["UNRATED", "FAILED"])
    fun `requeue resets processing state for unrated and failed transactions`(status: TransactionStatus) {
        val transaction = Fixtures.usageTransaction(status = status)
        val later = now.plusSeconds(3600)

        transaction.requeue(later)

        assertThat(transaction.status).isEqualTo(TransactionStatus.PENDING)
        assertThat(transaction.attempts).isZero()
        assertThat(transaction.nextAttemptAt).isEqualTo(later)
        assertThat(transaction.statusReason).isNull()
        assertThat(transaction.processedAt).isNull()
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus::class, names = ["PENDING", "RATED"])
    fun `requeue refuses transactions that are pending or already rated`(status: TransactionStatus) {
        val transaction = Fixtures.usageTransaction(status = status)

        assertThatThrownBy { transaction.requeue(now) }.isInstanceOf(ConflictException::class.java)
    }
}
