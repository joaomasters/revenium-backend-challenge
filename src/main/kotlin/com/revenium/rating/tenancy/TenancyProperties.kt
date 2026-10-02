package com.revenium.rating.tenancy

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.tenancy")
class TenancyProperties(apiKeys: List<String> = emptyList()) {

    private val tenantByApiKey: Map<String, String> = apiKeys
        .filter { it.isNotBlank() }
        .associate { entry ->
            val separator = entry.indexOf(':')
            require(separator > 0 && separator < entry.length - 1) {
                "Invalid app.tenancy.api-keys entry; expected 'tenantId:apiKey'"
            }
            entry.substring(separator + 1).trim() to entry.substring(0, separator).trim()
        }

    fun tenantForApiKey(apiKey: String?): String? = apiKey?.let { tenantByApiKey[it] }
}
