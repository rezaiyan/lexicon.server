package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** Which [IllegalArgumentException] messages reach the client (no Spring context needed). */
class GlobalExceptionHandlerMessageTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `should echo the message of a require failure in our own code`() {
        val error = runCatching { require(false) { "Image too large. Maximum size is 5MB." } }
            .exceptionOrNull() as IllegalArgumentException

        val response = handler.handleIllegalArgumentException(error)

        assertEquals(400, response.statusCode.value())
        assertEquals("Image too large. Maximum size is 5MB.", response.body?.message)
    }

    @Test
    fun `should hide the message of an exception raised inside the JDK or a library`() {
        val error = runCatching { UUID.fromString("not-a-uuid") }.exceptionOrNull() as IllegalArgumentException

        val response = handler.handleIllegalArgumentException(error)

        assertEquals(400, response.statusCode.value())
        assertEquals("Invalid request", response.body?.message)
    }
}
