package com.alirezaiyan.vokab.server.architecture

import com.alirezaiyan.vokab.server.Application
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Feature-module boundaries (Spring Modulith). Every direct sub-package of the application package
 * is a module; `shared` is the kernel and depends on no feature.
 *
 * The features still call each other synchronously, so the graph has cycles. Instead of failing on
 * them, [allowedDependencies] records today's graph: a new cross-feature dependency fails the build,
 * and removing one (events, item 17; service splits, item 18) fails it too until this map is
 * tightened, so the map only ever shrinks.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(Application::class.java)

    private val allowedDependencies = mapOf(
        "admin" to setOf(),
        "ai" to setOf("analytics", "notification", "study", "subscription", "user", "words"),
        "analytics" to setOf("subscription", "user"),
        "auth" to setOf("admin", "notification", "subscription", "user"),
        "email" to setOf(),
        "notification" to setOf("ai", "analytics", "study", "subscription", "user"),
        "shared" to setOf(),
        "study" to setOf("notification", "user", "words"),
        "subscription" to setOf("user"),
        "user" to setOf("auth", "notification", "study", "subscription"),
        "wordrush" to setOf("user"),
        "words" to setOf("user"),
    )

    private fun actualDependencies(): Map<String, Set<String>> =
        modules.associate { module ->
            module.name to module.getDirectDependencies(modules).uniqueModules()
                .map { it.name }
                .filter { it != "shared" && it != module.name }
                .toList()
                .toSet()
        }

    @Test
    fun `module dependencies match the recorded graph`() {
        assertEquals(allowedDependencies, actualDependencies().toSortedMap()) {
            "Cross-feature dependencies changed. A new edge needs a reason (prefer an event); " +
                "a removed edge means this map can be tightened."
        }
    }

    @Test
    fun `no module reaches into another module's internals`() {
        val nonCycle = modules.detectViolations().messages.filterNot { it.startsWith("Cycle detected") }
        assertTrue(nonCycle.isEmpty(), nonCycle.joinToString("\n"))
    }
}
