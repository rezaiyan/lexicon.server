package com.alirezaiyan.vokab.server.health

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Outcome of the most recent OpenRouter calls, so health can report the provider without making
 * its own (paid, slow) API calls on every probe.
 */
@Component
class AiCallTracker(private val clock: Clock) {

    @Volatile private var successAt: Instant? = null
    @Volatile private var failureAt: Instant? = null

    val lastSuccessAt: Instant? get() = successAt
    val lastFailureAt: Instant? get() = failureAt

    fun record(success: Boolean) {
        if (success) successAt = Instant.now(clock) else failureAt = Instant.now(clock)
    }

    /** The latest call failed and nothing has succeeded since. */
    val failing: Boolean
        get() {
            val failure = lastFailureAt ?: return false
            val success = lastSuccessAt ?: return true
            return failure.isAfter(success)
        }
}
