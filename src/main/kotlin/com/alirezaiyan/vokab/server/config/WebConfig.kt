package com.alirezaiyan.vokab.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock

@Configuration
@EnableWebMvc
class WebConfig

/**
 * Custom error controller to return JSON responses instead of HTML error pages
 * This prevents the "Circular view path [error]" exception
 */
@RestController
class CustomErrorController(
    private val clock: Clock
) : ErrorController {
    
    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val status = request.getAttribute("jakarta.servlet.error.status_code") as? Int 
            ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
        val message = request.getAttribute("jakarta.servlet.error.message") as? String 
            ?: "An error occurred"
        val path = request.getAttribute("jakarta.servlet.error.request_uri") as? String 
            ?: "unknown"
        
        val errorResponse = mapOf(
            "status" to status,
            "error" to HttpStatus.valueOf(status).reasonPhrase,
            "message" to message,
            "path" to path,
            "timestamp" to clock.millis()
        )
        
        return ResponseEntity.status(status).body(errorResponse)
    }
}

