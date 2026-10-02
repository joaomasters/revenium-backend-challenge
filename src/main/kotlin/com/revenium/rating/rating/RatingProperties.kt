package com.revenium.rating.rating

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.rating")
data class RatingProperties(
    val schedulerEnabled: Boolean = true,
    val dispatchOnCommit: Boolean = true,
    val pollInterval: Duration = Duration.ofSeconds(5),
    val batchSize: Int = 100,
    val maxAttempts: Int = 5,
    val retryBackoff: Duration = Duration.ofSeconds(30),
)
