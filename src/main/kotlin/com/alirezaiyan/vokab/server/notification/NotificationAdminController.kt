package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/notifications")
class NotificationAdminController(
    private val notificationEngagementService: NotificationEngagementService
) {
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<ApiResponse<NotificationAdminStatsDto>> {
        val stats = notificationEngagementService.getAdminStats()
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }
}
