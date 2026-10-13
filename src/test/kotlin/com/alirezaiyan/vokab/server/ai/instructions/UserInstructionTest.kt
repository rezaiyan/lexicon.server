package com.alirezaiyan.vokab.server.ai.instructions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserInstructionTest {

    @Test
    fun `of returns null for null, empty and whitespace-only input`() {
        assertNull(UserInstruction.of(null))
        assertNull(UserInstruction.of(""))
        assertNull(UserInstruction.of(" \n\t "))
    }

    @Test
    fun `of trims and collapses whitespace`() {
        assertEquals("only the verbs please", UserInstruction.of("  only the\n\nverbs   please ")?.text)
    }

    @Test
    fun `of strips zero-width, bidi and control characters`() {
        val sneaky = "only​ ver‮bs\u0007"

        assertEquals("only verbs", UserInstruction.of(sneaky)?.text)
    }

    @Test
    fun `of keeps the zero-width non-joiner Persian spelling needs`() {
        val persian = "واژه‌های آشپزی"

        assertEquals(persian, UserInstruction.of(persian)?.text)
    }

    @Test
    fun `of applies NFKC so look-alike full-width letters become plain ones`() {
        assertEquals("system", UserInstruction.of("ｓｙｓｔｅｍ")?.text)
    }

    @Test
    fun `of accepts exactly the maximum length`() {
        assertEquals(UserInstruction.MAX_LENGTH, UserInstruction.of("a".repeat(UserInstruction.MAX_LENGTH))?.text?.length)
    }

    @Test
    fun `of rejects text over the maximum length`() {
        assertThrows<IllegalArgumentException> { UserInstruction.of("a".repeat(UserInstruction.MAX_LENGTH + 1)) }
    }
}
