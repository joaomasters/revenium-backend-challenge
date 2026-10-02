package com.revenium.rating

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class UsageRatingApplication

fun main(args: Array<String>) {
    runApplication<UsageRatingApplication>(*args)
}
