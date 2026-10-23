package com.alirezaiyan.vokab.server.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupportMaskingTest {

    @Test
    fun `maskEmail keeps two leading characters and the domain`() {
        assertEquals("al***@gmail.com", maskEmail("alireza@gmail.com"))
    }

    @Test
    fun `maskEmail keeps one character of a short local part`() {
        assertEquals("a***@x.io", maskEmail("ab@x.io"))
    }

    @Test
    fun `maskEmail hides malformed addresses entirely`() {
        assertEquals("***", maskEmail("not-an-email"))
        assertEquals("***", maskEmail("@domain.com"))
    }
}
