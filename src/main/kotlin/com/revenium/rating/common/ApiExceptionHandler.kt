package com.revenium.rating.common

import com.revenium.rating.tenancy.MissingTenantContextException
import com.revenium.rating.tenancy.TenantMismatchException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(ex: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, ex.message)

    @ExceptionHandler(ConflictException::class)
    fun conflict(ex: ConflictException): ProblemDetail = problem(HttpStatus.CONFLICT, ex.message)

    @ExceptionHandler(PayloadTooLargeException::class)
    fun payloadTooLarge(ex: PayloadTooLargeException): ProblemDetail = problem(HttpStatus.CONTENT_TOO_LARGE, ex.message)

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(ex: InvalidRequestException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, ex.message)

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun malformedRequest(ex: Exception): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "Malformed request: ${ex.message}")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validationFailed(ex: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" })

    @ExceptionHandler(TenantMismatchException::class)
    fun tenantMismatch(ex: TenantMismatchException): ProblemDetail {
        log.warn("Tenant mismatch rejected: {}", ex.message)
        return problem(HttpStatus.FORBIDDEN, "Operation is not permitted for the authenticated tenant")
    }

    @ExceptionHandler(MissingTenantContextException::class)
    fun missingTenantContext(ex: MissingTenantContextException): ProblemDetail {
        log.error("Tenant context missing at a tenant-scoped boundary", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error")
    }

    private fun problem(status: HttpStatus, detail: String?): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail)
}
