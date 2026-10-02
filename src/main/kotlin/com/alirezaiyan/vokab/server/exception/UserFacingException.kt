package com.alirezaiyan.vokab.server.exception

/**
 * A failure whose message is written for the end user (e.g. "No vocabulary found in the image").
 * Its message is returned to clients as-is; any other exception's message stays in the logs.
 */
class UserFacingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The text that is safe to send to a client for this error.
 *
 * Only messages written for users pass through: [UserFacingException] and
 * [IllegalArgumentException] (the project's validation convention, mapped to 400). Anything
 * else (SQL, HTTP client, third-party API errors) may expose internals, so [fallback] is used.
 */
fun Throwable.clientMessage(fallback: String): String = when (this) {
    is UserFacingException, is IllegalArgumentException -> message?.takeIf { it.isNotBlank() } ?: fallback
    else -> fallback
}
