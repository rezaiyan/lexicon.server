package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/** Admin-only: requires X-Admin-Key (SecurityConfig guards the admin path prefix). */
@RestController
@RequestMapping("/admin/credits")
class CreditAdminController(
    private val creditService: CreditService,
    private val properties: CreditProperties,
) {
    /** Adds non-expiring bonus credits, e.g. to make up for a failed extraction reported to support. */
    @PostMapping("/grant")
    fun grant(
        @RequestParam userId: Long,
        @RequestParam amount: Int,
        @RequestParam(required = false) note: String?,
    ): ResponseEntity<ApiResponse<CreditBalanceResponse>> {
        logger.info { "Admin credit grant: userId=$userId, amount=$amount" }
        val balance = creditService.grantBonus(userId, amount, note)
        return ResponseEntity.ok(ApiResponse(success = true, data = balance.toResponse(properties)))
    }
}
