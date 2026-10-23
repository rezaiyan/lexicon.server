package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminSessionTokensTest {

    private fun props(key: String) = AppProperties().apply { security.adminApiKey = key }

    private val strongKey = "k".repeat(AdminSessionTokens.MIN_DASHBOARD_KEY_LENGTH)
    private val tokens = AdminSessionTokens(props(strongKey), fixedClock(TEST_NOW))

    @Test
    fun `matches only the configured key`() {
        assertTrue(tokens.keyMatches(strongKey))
        assertFalse(tokens.keyMatches(strongKey + "x"))
        assertFalse(tokens.keyMatches(""))
        assertFalse(tokens.keyMatches(null))
    }

    @Test
    fun `no configured key matches nothing`() {
        val unset = AdminSessionTokens(props(""), fixedClock(TEST_NOW))
        assertFalse(unset.keyMatches(""))
        assertFalse(unset.keyMatches("anything"))
    }

    @Test
    fun `an issued session validates until it expires`() {
        val session = tokens.issue()

        assertEquals(TEST_NOW.plus(AdminSessionTokens.SESSION_TTL), session.expiresAt)
        assertEquals(session.expiresAt, tokens.validate(session.token))

        val later = AdminSessionTokens(props(strongKey), fixedClock(session.expiresAt))
        assertNull(later.validate(session.token))
    }

    @Test
    fun `tampered, foreign or malformed tokens are rejected`() {
        val session = tokens.issue()
        val (exp, nonce, sig) = session.token.split('.')

        assertNull(tokens.validate("${exp.toLong() + 3600}.$nonce.$sig"))
        assertNull(tokens.validate("$exp.$nonce.${sig.reversed()}"))
        assertNull(tokens.validate("garbage"))
        assertNull(tokens.validate(null))
    }

    @Test
    fun `rotating the admin key ends existing sessions`() {
        val session = tokens.issue()
        val rotated = AdminSessionTokens(props("r".repeat(40)), fixedClock(TEST_NOW))
        assertNull(rotated.validate(session.token))
    }

    @Test
    fun `a short admin key disables dashboard sign-in and sessions`() {
        val weak = AdminSessionTokens(props("short-key"), fixedClock(TEST_NOW))
        assertFalse(weak.dashboardSignInEnabled())
        assertNull(weak.validate(weak.issue().token))
    }

    @Test
    fun `a short admin key disables key auth, so even the right key never matches`() {
        val weak = AdminSessionTokens(props("short-key"), fixedClock(TEST_NOW))
        assertFalse(weak.keyAuthEnabled())
        assertFalse(weak.keyMatches("short-key"))
    }
}
