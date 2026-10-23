package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.RateLimitExceededException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SelfPushGuardTest {

    private val guard = SelfPushGuard()

    @Test
    fun `accepts a plain test notification`() {
        assertDoesNotThrow { guard.check(1L, request()) }
    }

    @Test
    fun `rejects a server-reserved data key`() {
        assertThrows<IllegalArgumentException> { guard.validate(request(data = mapOf(NOTIFICATION_LOG_ID_KEY to "9"))) }
    }

    @Test
    fun `rejects a type that drives account or billing behaviour`() {
        assertThrows<IllegalArgumentException> { guard.validate(request(data = mapOf("type" to "account_deleted"))) }
    }

    @Test
    fun `rejects an oversized body`() {
        assertThrows<IllegalArgumentException> { guard.validate(request(body = "x".repeat(1001))) }
    }

    @Test
    fun `rejects a non-https image`() {
        assertThrows<IllegalArgumentException> { guard.validate(request(imageUrl = "http://10.0.0.1/a.png")) }
    }

    @Test
    fun `rate limits repeated sends per user`() {
        repeat(5) { guard.check(1L, request()) }

        assertThrows<RateLimitExceededException> { guard.check(1L, request()) }
    }

    @Test
    fun `one user's sends don't count against another`() {
        repeat(5) { guard.check(1L, request()) }

        assertDoesNotThrow { guard.check(2L, request()) }
    }

    private fun request(
        body: String = "Body",
        data: Map<String, String>? = null,
        imageUrl: String? = null,
    ) = SendNotificationRequest(title = "Title", body = body, data = data, imageUrl = imageUrl)
}
