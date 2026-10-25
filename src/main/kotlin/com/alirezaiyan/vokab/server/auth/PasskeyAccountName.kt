package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.User

/** How the device's passkey picker labels an account, so someone with several can tell them apart. */
data class PasskeyAccountName(val name: String, val displayName: String)

/**
 * The email, when it's one the user knows. A Hide My Email address (or Apple sharing none) means
 * nothing to them, so those accounts show their name with "Apple ID" instead.
 */
fun User.passkeyAccountName(): PasskeyAccountName {
    // UserProvisioning.newAppleUser names an account Apple shared no name for after its address
    val ownName = name.takeUnless { it.isBlank() || it == email.substringBefore("@") }
    val personName = ownName ?: displayAlias?.takeIf { it.isNotBlank() }
    if (email.substringAfter("@") !in HIDDEN_EMAIL_DOMAINS) return PasskeyAccountName(email, personName ?: email)
    val label = personName?.let { "$it (Apple ID)" } ?: "Apple ID"
    return PasskeyAccountName(label, personName ?: label)
}

/** Apple's relay, and the placeholder UserProvisioning stores when Apple shares no email. */
private val HIDDEN_EMAIL_DOMAINS = setOf("privaterelay.appleid.com", "apple.hidden")
