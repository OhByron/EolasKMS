package dev.kosha.app.config

import dev.kosha.common.api.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(
                type = "https://kosha.dev/errors/not-found",
                title = "Resource not found",
                status = 404,
                detail = ex.message,
            ),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError(
                type = "https://kosha.dev/errors/bad-request",
                title = "Bad request",
                status = 400,
                detail = ex.message,
            ),
        )

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(
                type = "https://kosha.dev/errors/conflict",
                title = "Conflict",
                status = 409,
                detail = ex.message,
            ),
        )

    @ExceptionHandler(AccessDeniedException::class)
    fun handleForbidden(ex: AccessDeniedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiError(
                type = "https://kosha.dev/errors/forbidden",
                title = "Forbidden",
                status = 403,
                detail = "You do not have permission to perform this action",
            ),
        )

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(
                type = "https://kosha.dev/errors/not-found",
                title = "Not found",
                status = 404,
                detail = ex.message,
            ),
        )
}
