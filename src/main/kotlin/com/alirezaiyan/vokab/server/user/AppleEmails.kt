package com.alirezaiyan.vokab.server.user

/** Stored as the email of an Apple account that shared none (see UserProvisioning). */
const val APPLE_PLACEHOLDER_EMAIL_DOMAIN = "apple.hidden"

/**
 * Hide My Email relay domains of Sign in with Apple: the original one, and the one Apple moved new
 * addresses to in 2026. Existing relay addresses keep working, so both stay.
 */
private val APPLE_RELAY_EMAIL_DOMAINS = setOf("privaterelay.appleid.com", "private.icloud.com")

/** A stand-in address, not one that reaches anyone. */
fun isApplePlaceholderEmail(email: String): Boolean = email.domain() == APPLE_PLACEHOLDER_EMAIL_DOMAIN

/** An address the user won't recognize as theirs: an Apple relay address, or a stand-in. */
fun isHiddenAppleEmail(email: String): Boolean =
    email.domain() in APPLE_RELAY_EMAIL_DOMAINS || isApplePlaceholderEmail(email)

private fun String.domain(): String = substringAfterLast("@").lowercase()
