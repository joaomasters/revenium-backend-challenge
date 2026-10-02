package com.revenium.rating.invoicing

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Billing periods are calendar months in UTC. A period is stored as the date of its first day. */
object BillingPeriods {

    fun containing(instant: Instant): YearMonth = YearMonth.from(instant.atZone(ZoneOffset.UTC))

    fun start(period: YearMonth): Instant = period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    /** Exclusive end of the period. */
    fun end(period: YearMonth): Instant = start(period.plusMonths(1))
}
