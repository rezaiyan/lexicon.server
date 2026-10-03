package com.alirezaiyan.vokab.server.exception

/**
 * A failure whose message is written for the end user (e.g. "No vocabulary found in the image").
 * [GlobalExceptionHandler] returns its message to clients as-is (400); any other exception's
 * message stays in the logs.
 */
class UserFacingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

