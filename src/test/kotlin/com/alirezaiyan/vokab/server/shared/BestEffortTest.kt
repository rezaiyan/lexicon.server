package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BestEffortTest {

    @Test
    fun `returns the value when the lookup succeeds`() {
        assertEquals("de", bestEffort("Primary language") { "de" })
    }

    @Test
    fun `a failed lookup is absorbed as no value`() {
        assertNull(bestEffort<String>("Primary language") { error("database down") })
    }
}
