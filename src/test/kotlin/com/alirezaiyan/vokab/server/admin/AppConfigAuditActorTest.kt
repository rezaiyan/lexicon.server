package com.alirezaiyan.vokab.server.admin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppConfigAuditActorTest {

    @Test
    fun `the authenticated admin principal is recorded, not the header`() {
        assertEquals("cli", auditActor("cli", null))
    }

    @Test
    fun `a client-supplied name is kept only as a labelled note`() {
        assertEquals("cli (client-supplied: alice)", auditActor("cli", "alice"))
    }

    @Test
    fun `control characters in the client note are dropped`() {
        assertEquals("cli (client-supplied: ab)", auditActor("cli", "a\r\nb"))
    }
}
