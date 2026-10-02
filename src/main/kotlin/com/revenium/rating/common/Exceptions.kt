package com.revenium.rating.common

/** A tenant-scoped resource does not exist (or belongs to another tenant, which is reported identically). */
class NotFoundException(message: String) : RuntimeException(message)

/** The request is well-formed but conflicts with the current state of the resource. */
class ConflictException(message: String) : RuntimeException(message)

/** The request is semantically invalid. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** The request body exceeds the configured limit. */
class PayloadTooLargeException(limitBytes: Int) : RuntimeException("Request body exceeds $limitBytes bytes")
