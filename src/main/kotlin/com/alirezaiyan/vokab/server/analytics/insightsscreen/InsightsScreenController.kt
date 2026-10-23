package com.alirezaiyan.vokab.server.analytics.insightsscreen

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/v1/analytics")
class InsightsScreenController(private val service: InsightsScreenService) {

    @GetMapping("/insights-screen")
    fun getInsightsScreen(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(required = false) tz: String?,
    ): ResponseEntity<ApiResponse<InsightsScreenResponse>> =
        ResponseEntity.ok(ApiResponse(success = true, data = service.build(user.id, parseZone(tz))))
}

internal fun parseZone(tz: String?): ZoneId =
    tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
