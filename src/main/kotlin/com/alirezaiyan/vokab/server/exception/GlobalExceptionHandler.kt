package com.alirezaiyan.vokab.server.exception

import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.ErrorResponse as SpringErrorResponse

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "IllegalArgumentException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, message = ex.message ?: "Invalid argument"))
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "AuthenticationException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse(success = false, message = "Authentication failed"))
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentialsException(ex: BadCredentialsException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "BadCredentialsException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse(success = false, message = "Invalid credentials"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Unit>> {
        val errors = ex.bindingResult.allErrors.joinToString(", ") { error ->
            val fieldName = (error as? FieldError)?.field ?: "unknown"
            val errorMessage = error.defaultMessage ?: "Invalid value"
            "$fieldName: $errorMessage"
        }
        logger.warn { "Validation error: $errors" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, message = "Validation failed: $errors"))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(ex: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "HttpMessageNotReadableException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, message = "Invalid request body"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNoSuchElementException(ex: NoSuchElementException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "NoSuchElementException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse(success = false, message = "Resource not found"))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolationException(ex: DataIntegrityViolationException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "DataIntegrityViolationException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiResponse(success = false, message = "Resource already exists"))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatchException(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "Type mismatch for parameter '${ex.name}': ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse(success = false, message = "Invalid value for parameter '${ex.name}'"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ApiResponse<Unit>> {
        // Spring MVC request errors (unknown route, missing parameter, wrong method, unsupported
        // media type, ...) carry their own status; they are client errors, not server failures.
        if (ex is SpringErrorResponse) {
            logger.warn { "${ex.javaClass.simpleName}: ${ex.message}" }
            val message = ex.body.detail ?: HttpStatus.resolve(ex.statusCode.value())?.reasonPhrase ?: "Request failed"
            return ResponseEntity
                .status(ex.statusCode)
                .headers(ex.headers)
                .body(ApiResponse(success = false, message = message))
        }
        logger.error(ex) { "Unhandled exception: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse(
                success = false,
                message = "An unexpected error occurred"
            ))
    }

    @ExceptionHandler(org.hibernate.LazyInitializationException::class)
    fun handleLazyInitializationException(ex: org.hibernate.LazyInitializationException): ResponseEntity<ApiResponse<Unit>> {
        logger.error(ex) { "LazyInitializationException - entity not properly mapped to DTO" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse(
                success = false,
                message = "Internal server error - data mapping issue"
            ))
    }
}
