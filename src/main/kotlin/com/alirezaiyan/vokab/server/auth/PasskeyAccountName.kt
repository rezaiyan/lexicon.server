package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.isHiddenAppleEmail
import com.alirezaiyan.vokab.server.user.ownName

/** How the device's passkey picker labels an account, so someone with several can tell them apart. */
data class PasskeyAccountName(val name: String, val displayName: String)

/**
 * The email, when it's one the user knows. A Hide My Email address (or Apple sharing none) means
 * nothing to them, so those accounts show their name with "Apple ID" instead.
 */
fun User.passkeyAccountName(): PasskeyAccountName {
    val personName = ownName ?: displayAlias?.takeIf { it.isNotBlank() }
    if (!isHiddenAppleEmail(email)) return PasskeyAccountName(email, personName ?: email)
    val label = personName?.let { "$it (Apple ID)" } ?: "Apple ID"
    return PasskeyAccountName(label, personName ?: label)
}
