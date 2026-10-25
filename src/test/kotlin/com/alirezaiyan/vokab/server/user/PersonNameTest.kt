package com.alirezaiyan.vokab.server.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PersonNameTest {

    @Test
    fun `a real name is the person's name`() {
        assertEquals("Jane Doe", User(email = "jane@example.com", name = "Jane Doe").personName)
    }

    @Test
    fun `the alias the user picked wins over the name`() {
        val user = User(email = "jane@example.com", name = "Jane Doe").apply { displayAlias = "JD" }

        assertEquals("JD", user.personName)
    }

    @Test
    fun `a blank name means no name`() {
        assertNull(User(email = "x7k2@private.icloud.com", name = " ").personName)
    }

    @Test
    fun `a name made from the email address is no name`() {
        assertNull(User(email = "525tbwzjhz@privaterelay.appleid.com", name = "525tbwzjhz").ownName)
        assertNull(User(email = "pat@example.com", name = "pat@example.com").ownName)
    }

    @Test
    fun `a real address whose name matches it keeps the name`() {
        assertEquals("Ali", User(email = "ali@gmail.com", name = "Ali").ownName)
    }

    @Test
    fun `a blank alias falls back to the own name`() {
        val user = User(email = "jane@example.com", name = "Jane").apply { displayAlias = "" }

        assertEquals("Jane", user.personName)
    }
}
