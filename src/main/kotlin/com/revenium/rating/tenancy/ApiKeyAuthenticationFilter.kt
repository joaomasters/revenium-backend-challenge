package com.revenium.rating.tenancy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the tenant from the X-API-Key header and binds it for the duration of the request.
 * The tenantId inside a payload is never trusted on its own; it must match the tenant resolved here.
 */
@Component
class ApiKeyAuthenticationFilter(private val tenancyProperties: TenancyProperties) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val tenantId = tenancyProperties.tenantForApiKey(request.getHeader(API_KEY_HEADER))
        if (tenantId == null) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write(
                """{"title":"Unauthorized","status":401,"detail":"Missing or unknown $API_KEY_HEADER header"}"""
            )
            return
        }
        TenantContext.runAs(tenantId) { filterChain.doFilter(request, response) }
    }

    companion object {
        const val API_KEY_HEADER = "X-API-Key"
    }
}
