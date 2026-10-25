package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasskeyAccountNameTest {

    @Test
    fun `an account with a real email is named by it`() {
        val user = User(email = "pat@gmail.com", name = "Pat Lee")

        assertEquals(PasskeyAccountName(name = "pat@gmail.com", displayName = "Pat Lee"), user.passkeyAccountName())
    }

    @Test
    fun `a Hide My Email account is named by its alias, not the random address`() {
        // newAppleUser names an account without a name after its address
        val user = User(email = "525tbwzjhz@privaterelay.appleid.com", name = "525tbwzjhz").apply { displayAlias = "Ali" }

        assertEquals(PasskeyAccountName(name = "Ali (Apple ID)", displayName = "Ali"), user.passkeyAccountName())
    }

    @Test
    fun `a Hide My Email account prefers the name Apple shared`() {
        val user = User(email = "x7k2@privaterelay.appleid.com", name = "Jane Doe").apply { displayAlias = "jd" }

        assertEquals("Jane Doe (Apple ID)", user.passkeyAccountName().name)
    }

    @Test
    fun `an Apple account with no email and no name is still labelled`() {
        val user = User(email = "apple_001_abc@apple.hidden", name = "apple_001_abc")

        assertEquals(PasskeyAccountName(name = "Apple ID", displayName = "Apple ID"), user.passkeyAccountName())
    }
}
