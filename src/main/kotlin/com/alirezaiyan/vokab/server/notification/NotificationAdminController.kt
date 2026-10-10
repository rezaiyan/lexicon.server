package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/notifications")
class NotificationAdminController(
    private val notificationEngagementService: NotificationEngagementService,
    private val notificationAdminService: NotificationAdminService,
    private val manualPushService: ManualPushService,
) {
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<ApiResponse<NotificationAdminStatsDto>> {
        val stats = notificationEngagementService.getAdminStats()
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }

    @GetMapping("/audience")
    fun getAudience(): ResponseEntity<ApiResponse<NotificationAudienceDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = notificationAdminService.getAudience()))

    @GetMapping("/logs")
    fun getLogs(
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<ApiResponse<NotificationLogPageDto>> {
        val logs = notificationAdminService.getLogs(userId, type, page, size)
        return ResponseEntity.ok(ApiResponse(success = true, data = logs))
    }

    @GetMapping("/logs/types")
    fun getLogTypes(): ResponseEntity<ApiResponse<List<String>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = notificationAdminService.getLogTypes()))

    /** [query] is a user id or an email. */
    @GetMapping("/users/lookup")
    fun lookupUser(@RequestParam query: String): ResponseEntity<ApiResponse<NotificationUserDetailDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = notificationAdminService.getUserDetail(query)))

    @PostMapping("/users/{userId}/clear-suppression")
    fun clearSuppression(@PathVariable userId: Long): ResponseEntity<ApiResponse<NotificationUserDetailDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = notificationAdminService.clearSuppression(userId)))

    @PostMapping("/send")
    fun sendManual(@Valid @RequestBody request: ManualPushRequest): ResponseEntity<ApiResponse<ManualPushResultDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = manualPushService.send(request)))

    @PostMapping("/jobs/{job}/run")
    fun runJob(@PathVariable job: NotificationJob): ResponseEntity<ApiResponse<NotificationJobRunDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = notificationAdminService.runJob(job)))
}
