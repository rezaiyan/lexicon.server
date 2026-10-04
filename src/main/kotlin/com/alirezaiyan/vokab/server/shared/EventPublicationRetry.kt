package com.alirezaiyan.vokab.server.shared

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Re-runs event listeners that failed. A publication stays in `event_publication` until its listener
 * completes; this resubmits the ones older than [MIN_AGE] (so in-flight listeners aren't run twice)
 * and younger than [MAX_AGE] (a listener still failing after that needs a fix, not more retries).
 */
@Component
class EventPublicationRetry(
    private val incompletePublications: IncompleteEventPublications,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    fun resubmitFailed() {
        val now = Instant.now(clock)
        var resubmitted = 0
        incompletePublications.resubmitIncompletePublications { publication ->
            val date = publication.publicationDate
            (date.isBefore(now.minus(MIN_AGE)) && date.isAfter(now.minus(MAX_AGE))).also { if (it) resubmitted++ }
        }
        if (resubmitted > 0) {
            meterRegistry.counter("events.resubmitted").increment(resubmitted.toDouble())
            logger.warn { "Resubmitted $resubmitted incomplete event publication(s)" }
        }
    }

    companion object {
        val MIN_AGE: Duration = Duration.ofMinutes(5)
        val MAX_AGE: Duration = Duration.ofDays(3)
    }
}
