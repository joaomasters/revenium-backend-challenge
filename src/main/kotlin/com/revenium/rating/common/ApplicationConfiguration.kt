package com.revenium.rating.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
class ApplicationConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
