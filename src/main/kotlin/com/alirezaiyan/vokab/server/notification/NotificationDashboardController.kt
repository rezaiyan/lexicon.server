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
 * Serves the admin dashboards (push notifications, support inbox). These files are public and hold no data: the
 * pages sign in at `/admin/session` and read `/admin/...` with the session cookie.
 */
@Hidden
@RestController
class NotificationDashboardController {

    @GetMapping("/admin-ui/notifications", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): ResponseEntity<Resource> = serve("notifications.html", MediaType.TEXT_HTML)

    @GetMapping("/admin-ui/notifications.js", produces = ["text/javascript"])
    fun script(): ResponseEntity<Resource> = serve("notifications.js", MediaType.parseMediaType("text/javascript"))

    @GetMapping("/admin-ui/notifications.css", produces = ["text/css"])
    fun styles(): ResponseEntity<Resource> = serve("notifications.css", MediaType.parseMediaType("text/css"))

    /** Support inbox; shares the dashboard stylesheet and session sign-in. */
    @GetMapping("/admin-ui/support", produces = [MediaType.TEXT_HTML_VALUE])
    fun supportPage(): ResponseEntity<Resource> = serve("support.html", MediaType.TEXT_HTML)

    @GetMapping("/admin-ui/support.js", produces = ["text/javascript"])
    fun supportScript(): ResponseEntity<Resource> = serve("support.js", MediaType.parseMediaType("text/javascript"))

    /** Comeback rescue taste test, plans and funnel; same stylesheet and session sign-in. */
    @GetMapping("/admin-ui/rescue", produces = [MediaType.TEXT_HTML_VALUE])
    fun rescuePage(): ResponseEntity<Resource> = serve("rescue.html", MediaType.TEXT_HTML)

    @GetMapping("/admin-ui/rescue.js", produces = ["text/javascript"])
    fun rescueScript(): ResponseEntity<Resource> = serve("rescue.js", MediaType.parseMediaType("text/javascript"))

    private fun serve(file: String, type: MediaType): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .contentType(type)
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header("Cross-Origin-Opener-Policy", "same-origin")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            .body(ClassPathResource("admin-ui/$file"))

    companion object {
        /** Own-origin scripts and styles only: no inline code, no framing, no third-party calls. */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
                "img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
    }
}
