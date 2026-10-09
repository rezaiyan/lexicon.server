package com.alirezaiyan.vokab.server.shared

/**
 * Machine-readable `code` values in error responses. Clients branch on these, never on `message`,
 * so the strings are part of the API contract: add new ones freely, never rename.
 */
object ApiErrorCode {
    const val PREMIUM_REQUIRED = "PREMIUM_REQUIRED"
    const val INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS"
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

/**
 * Not enough AI credits for the action. Mapped to 402 like [PremiumRequiredException], told apart
 * by its code: the app shows the balance and offers more credits instead of a plain paywall.
 */
class InsufficientCreditsException(action: String, val required: Int, val available: Int) :
    RuntimeException("Not enough credits for $action: needs $required, has $available")

/**
 * The caller exhausted a rate-limit bucket. Mapped to 429, with a `Retry-After` header when
 * [retryAfterSeconds] is known.
 */
class RateLimitExceededException(
    message: String = "Rate limit exceeded. Please try again later.",
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message) {
    companion object {
        /** A rejection that resets in [seconds]; the message rounds up to whole minutes. */
        fun retryIn(seconds: Long): RateLimitExceededException {
            val minutes = maxOf(1, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
            val unit = if (minutes == 1L) "minute" else "minutes"
            return RateLimitExceededException("Too many requests. Try again in $minutes $unit.", seconds)
        }

        private const val SECONDS_PER_MINUTE = 60L
    }
}
