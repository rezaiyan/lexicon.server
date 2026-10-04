package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.auth.AuthService
import com.alirezaiyan.vokab.server.subscription.SubscriptionExpired
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.requireId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Spring Modulith registry on PostgreSQL: a committed event is stored in `event_publication`,
 * its listener runs after commit, and the completed publication is deleted; a rolled-back one never
 * reaches a listener.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EventPublicationIntegrationTest.ProbeListener::class)
class EventPublicationIntegrationTest {

    /** Test-only event, so the failure path doesn't depend on any production listener breaking. */
    data class ProbeEvent(val marker: Long, override val occurredAt: Instant) : DomainEvent

    // @Component so the kotlin-spring plugin opens it for the transactional listener proxy
    @Component
    class ProbeListener {
        @Volatile var failNext = false
        val attempts = AtomicInteger()
        val delivered = CopyOnWriteArrayList<Long>()

        @ApplicationModuleListener
        fun on(event: ProbeEvent) {
            attempts.incrementAndGet()
            if (failNext) {
                failNext = false
                error("probe listener failure")
            }
            delivered += event.marker
        }
    }

    @Autowired lateinit var probeListener: ProbeListener
    @Autowired lateinit var incompletePublications: IncompleteEventPublications
    @Autowired lateinit var publisher: DomainEventPublisher
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var authService: AuthService

    private var userId: Long = 0

    private fun user(): Long {
        val user = testUserHelper.saveAndCommit(User(email = "events-${System.nanoTime()}@example.com", name = "Events"))
        return user.requireId().also { userId = it }
    }

    @AfterEach
    fun cleanUp() {
        if (userId != 0L) {
            jdbcTemplate.update("DELETE FROM app_events WHERE user_id = ?", userId)
            authService.deleteAccount(userId)
        }
    }

    @Test
    fun `a committed event reaches its listener and the publication is completed`() {
        val id = user()

        transactionTemplate.executeWithoutResult {
            publisher.publish(SubscriptionExpired(id, "annual", Instant.now()))
        }

        awaitUntil { expiredEvents(id) == 1 && openPublications() == 0 }
        assertEquals(1, expiredEvents(id))
        assertEquals(0, openPublications())
    }

    @Test
    fun `a rolled back event is never delivered`() {
        val id = user()

        transactionTemplate.executeWithoutResult { status ->
            publisher.publish(SubscriptionExpired(id, "annual", Instant.now()))
            status.setRollbackOnly()
        }

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(0, expiredEvents(id))
        assertEquals(0, openPublications())
    }

    @Test
    fun `a failed listener leaves the publication stored until a resubmission succeeds`() {
        val marker = System.nanoTime()
        probeListener.failNext = true

        transactionTemplate.executeWithoutResult { publisher.publish(ProbeEvent(marker, Instant.now())) }

        awaitUntil { probeListener.attempts.get() == 1 }
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, probePublications(marker))

        incompletePublications.resubmitIncompletePublications { (it.event as? ProbeEvent)?.marker == marker }

        awaitUntil { probePublications(marker) == 0 }
        assertEquals(listOf(marker), probeListener.delivered)
    }

    private fun probePublications(marker: Long): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
        Int::class.java,
        ProbeEvent::class.java.name,
        "%\"marker\":$marker,%",
    ) ?: 0

    private fun expiredEvents(id: Long): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM app_events WHERE user_id = ? AND event_name = 'subscription_expired'",
        Int::class.java,
        id,
    ) ?: 0

    /** Publications of this test's event that are stored but not completed (completed ones are deleted). */
    private fun openPublications(): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
        Int::class.java,
        SubscriptionExpired::class.java.name,
        "%\"userId\":$userId,%",
    ) ?: 0

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Event listener did not complete within 10s" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 20L

        // Nothing to poll for when the expected outcome is "nothing happened"; long enough for an
        // after-commit async listener to have run if it had been (wrongly) triggered.
        const val SETTLE_MILLIS = 500L
    }
}
