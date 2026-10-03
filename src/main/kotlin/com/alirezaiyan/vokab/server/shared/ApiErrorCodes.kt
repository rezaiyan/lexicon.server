package com.alirezaiyan.vokab.server.shared

/**
 * Machine-readable `code` values in error responses. Clients branch on these, never on `message`,
 * so the strings are part of the API contract: add new ones freely, never rename.
 */
object ApiErrorCode {
    const val PREMIUM_REQUIRED = "PREMIUM_REQUIRED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"
}

/**
 * A third-party service we depend on (Apple, OpenRouter, ...) failed or answered unusably.
 * Mapped to 502: the request was fine and may succeed on retry. The message is for logs only.
 */
class UpstreamServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The feature needs an active premium subscription or grant. Mapped to 402 Payment Required.
 *
 * Deliberately not 403: the app treats 401/403 as "session invalid" and refreshes its tokens,
 * whereas this case should open the paywall.
 */
class PremiumRequiredException(feature: String) :
    RuntimeException("Premium subscription required to use $feature")

/** The caller exhausted a rate-limit bucket. Mapped to 429. */
class RateLimitExceededException(message: String = "Rate limit exceeded. Please try again later.") :
    RuntimeException(message)
