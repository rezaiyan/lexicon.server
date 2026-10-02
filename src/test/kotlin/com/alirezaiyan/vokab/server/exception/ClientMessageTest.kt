package com.alirezaiyan.vokab.server.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClientMessageTest {

    @Test
    fun `user facing message is passed through`() {
        val error = UserFacingException("No vocabulary found in the image.")

        assertEquals("No vocabulary found in the image.", error.clientMessage("Failed"))
    }

    @Test
    fun `validation message is passed through`() {
        assertEquals("Alias already taken", IllegalArgumentException("Alias already taken").clientMessage("Failed"))
    }

    @Test
    fun `internal error message is hidden behind the fallback`() {
        val error = RuntimeException("OpenRouter error: invalid api key sk-or-123")

        assertEquals("Failed to translate text", error.clientMessage("Failed to translate text"))
    }

    @Test
    fun `blank or missing user message falls back`() {
        assertEquals("Failed", IllegalArgumentException().clientMessage("Failed"))
        assertEquals("Failed", UserFacingException(" ").clientMessage("Failed"))
    }
}
