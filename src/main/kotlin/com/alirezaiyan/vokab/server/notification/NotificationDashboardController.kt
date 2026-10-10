package com.alirezaiyan.vokab.server.notification

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the push notification admin dashboard. The page itself is public and holds no data:
 * it asks for the admin API key and calls `/admin/notifications/` with `X-Admin-Key`.
 */
@Hidden
@RestController
class NotificationDashboardController {
    private val page: Resource = ClassPathResource("admin-ui/notifications.html")

    @GetMapping("/admin-ui/notifications", produces = [MediaType.TEXT_HTML_VALUE])
    fun dashboard(): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Frame-Options", "DENY")
            .header("Referrer-Policy", "no-referrer")
            .body(page)
}
