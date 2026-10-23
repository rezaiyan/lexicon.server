package com.alirezaiyan.vokab.server.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserEmailNormalizationTest {

    @Test
    fun `a new user stores its email trimmed and lowercased`() {
        val user = testUser(email = "  Jane.Doe@Example.COM ")

        assertEquals("jane.doe@example.com", user.email)
    }

    @Test
    fun `changing a user's email stores it normalized`() {
        val user = testUser()

        user.email = "New@Example.com"

        assertEquals("new@example.com", user.email)
    }

    private fun testUser(email: String = "user@example.com") = User(email = email, name = "Test")
}
