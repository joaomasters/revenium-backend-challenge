package com.revenium.rating.common

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
    info = Info(title = "Usage Rating and Invoice Reconciliation API", version = "v1"),
    security = [SecurityRequirement(name = "apiKey")],
)
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, `in` = SecuritySchemeIn.HEADER, paramName = "X-API-Key")
class OpenApiConfiguration
