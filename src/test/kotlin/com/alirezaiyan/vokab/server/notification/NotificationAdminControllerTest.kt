package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(NotificationAdminController::class, NotificationDashboardController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class NotificationAdminControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var notificationEngagementService: NotificationEngagementService

    @MockitoBean
    private lateinit var notificationAdminService: NotificationAdminService

    @MockitoBean
    private lateinit var manualPushService: ManualPushService

    private val admin = UsernamePasswordAuthenticationToken(
        "ali-cli", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
    )
    private val regularUser = UsernamePasswordAuthenticationToken("user", null, emptyList())

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    @Test
    fun `admin endpoints reject non-admin callers`() {
        mockMvc.post("/admin/notifications/send") {
            with(authentication(regularUser))
            contentType = MediaType.APPLICATION_JSON
            content = """{"audience":"ALL","title":"t","body":"b"}"""
        }.andExpect { status { isForbidden() } }

        verify(manualPushService, never()).send(anyArg())
    }

    @Test
    fun `stats come from the engagement service`() {
        `when`(notificationEngagementService.getAdminStats()).thenReturn(
            NotificationAdminStatsDto(
                totalActiveSchedules = 3,
                suppressedUsers = SuppressedUsersDto(day3 = 1, day7 = 0, day14 = 0, day30 = 0),
                last7Days = Last7DaysDto(totalSent = 10, totalOpened = 4, openRatePercent = 40, typeBreakdown = emptyMap()),
            )
        )

        mockMvc.get("/admin/notifications/stats") {
            with(authentication(admin))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.totalActiveSchedules") { value(3) }
            jsonPath("$.data.last7Days.openRatePercent") { value(40) }
        }
    }

    @Test
    fun `send passes the request through and returns the result`() {
        `when`(manualPushService.send(anyArg())).thenReturn(
            ManualPushResultDto(
                dryRun = false, targeted = 1, sent = 1, failed = 0, skipped = 0,
                recipients = listOf(ManualPushRecipientDto(7L, "user 7", ManualPushOutcome.SENT, devicesReached = 1)),
                recipientsTruncated = false,
            )
        )

        mockMvc.post("/admin/notifications/send") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"userIds":[7],"title":"Hi","body":"There","data":{"deep_link":"lexicon://review"}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.sent") { value(1) }
            jsonPath("$.data.recipients[0].outcome") { value("SENT") }
        }
    }

    @Test
    fun `send rejects a blank title`() {
        mockMvc.post("/admin/notifications/send") {
            with(authentication(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"userIds":[7],"title":" ","body":"There"}"""
        }.andExpect { status { isBadRequest() } }

        verify(manualPushService, never()).send(anyArg())
    }

    @Test
    fun `logs are paged with optional filters`() {
        `when`(notificationAdminService.getLogs(7L, "MANUAL", 0, 50)).thenReturn(
            NotificationLogPageDto(
                items = listOf(
                    NotificationLogDto(
                        1L, 7L, "MANUAL", "Hi", "There", Instant.parse("2026-10-01T10:00:00Z"), null, null
                    )
                ),
                page = 0, size = 50, totalItems = 1, totalPages = 1,
            )
        )

        mockMvc.get("/admin/notifications/logs?userId=7&type=MANUAL") {
            with(authentication(admin))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].notificationType") { value("MANUAL") }
            jsonPath("$.data.totalItems") { value(1) }
        }
    }

    @Test
    fun `jobs run by name`() {
        `when`(notificationAdminService.runJob(NotificationJob.SMART_DISPATCH)).thenReturn(
            NotificationJobRunDto(NotificationJob.SMART_DISPATCH, Instant.parse("2026-10-01T10:00:00Z"), 12)
        )

        mockMvc.post("/admin/notifications/jobs/SMART_DISPATCH/run") {
            with(authentication(admin))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.job") { value("SMART_DISPATCH") }
        }
    }

    @Test
    fun `the dashboard files are served without credentials under a strict CSP`() {
        mapOf(
            "/admin-ui/notifications" to "text/html",
            "/admin-ui/notifications.js" to "text/javascript",
            "/admin-ui/notifications.css" to "text/css",
            "/admin-ui/rescue" to "text/html",
            "/admin-ui/rescue.js" to "text/javascript",
        ).forEach { (path, type) ->
            mockMvc.get(path).andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(type) }
                header { string("Cache-Control", "no-store") }
                header { string("Content-Security-Policy", NotificationDashboardController.CONTENT_SECURITY_POLICY) }
                header { string("X-Content-Type-Options", "nosniff") }
            }
        }
    }

    @Test
    fun `the dashboard has no inline script or style for the CSP to block`() {
        listOf("notifications", "rescue").forEach { page ->
            val html = mockMvc.get("/admin-ui/$page").andReturn().response.contentAsString
            val js = mockMvc.get("/admin-ui/$page.js").andReturn().response.contentAsString

            assert(!Regex("<script>|<style|style=\"|\\son[a-z]+=").containsMatchIn(html)) { "inline code in $page page" }
            assert(!js.contains("style=\"")) { "inline style attribute generated by $page.js" }
            assert(!js.contains("X-Admin-Key") && !js.contains("lexicon.adminKey")) { "admin key handled in $page.js" }
        }
    }
}
