package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.shared.ApiErrorCode
import com.alirezaiyan.vokab.server.shared.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val logger = KotlinLogging.logger {}

/**
 * Maps the instruction guard's outcomes to responses the app branches on by `code`. Lives with
 * the guard (shared must not know about ai); ordered first so the generic handler never wins.
 * Nothing was charged in any of these cases.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class InstructionExceptionHandler {

    @ExceptionHandler(InstructionRejectedException::class)
    fun handleRejected(ex: InstructionRejectedException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ApiResponse(success = false, message = "These instructions can't be used.", code = ex.reason.code))

    @ExceptionHandler(InstructionsPausedException::class)
    fun handlePaused(ex: InstructionsPausedException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
            .body(ApiResponse(
                success = false,
                message = "Custom instructions are paused for a while.",
                code = ApiErrorCode.INSTRUCTIONS_PAUSED,
            ))

    @ExceptionHandler(InstructionsUnavailableException::class)
    fun handleUnavailable(ex: InstructionsUnavailableException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn { "InstructionsUnavailableException: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse(
                success = false,
                message = "Custom instructions are unavailable right now.",
                code = ApiErrorCode.INSTRUCTIONS_UNAVAILABLE,
            ))
    }
}
