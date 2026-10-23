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
        "ai" to setOf("analytics", "credits", "notification", "study", "user", "words"),
        // Insights screen snapshot reads word stage counts and the Word Rush summary (read-only queries)
        // and, for practice nudges, when Word Rush and Listening were last used (read-only queries)
        "analytics" to setOf("listening", "subscription", "user", "wordrush", "words"),
        "auth" to setOf("admin", "notification", "subscription", "user"),
        // Reads the access level to size the monthly allowance
        "credits" to setOf("subscription"),
        "email" to setOf(),
        "listening" to setOf("user"),
        // MCP tools are a second front door onto the word and progress services, like the REST
        // controllers; its OAuth sign-in verifies Google ID tokens and finds the existing account
        "mcp" to setOf("analytics", "auth", "user", "words"),
        "notification" to setOf("ai", "analytics", "study", "subscription", "user"),
        "shared" to setOf(),
        "study" to setOf("notification", "user", "words"),
        "subscription" to setOf("user"),
        // Pushes support replies to the user; reads the user's display name and email for the admin label
        "support" to setOf("notification", "user"),
        "user" to setOf("auth", "notification", "study", "subscription"),
        "wordrush" to setOf("user"),
        "words" to setOf("user"),
    )

    private fun actualDependencies(): Map<String, Set<String>> =
        modules.associate { module ->
            module.identifier.toString() to module.getDirectDependencies(modules).uniqueModules()
                .map { it.identifier.toString() }
                .filter { it != "shared" && it != module.identifier.toString() }
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

    /**
     * MCP tools are free to use: the AI client does any extracting or translating itself. So neither
     * the tools nor the services they call (words, analytics) may touch the paid AI calls or credits.
     */
    @Test
    fun `mcp tools never reach the AI or credits modules`() {
        val actual = actualDependencies()
        for (module in listOf("mcp", "words", "analytics")) {
            val paid = actual.getValue(module).intersect(setOf("ai", "credits"))
            assertTrue(paid.isEmpty(), "$module depends on $paid: MCP tools must not trigger AI calls")
        }
    }

    @Test
    fun `no module reaches into another module's internals`() {
        val nonCycle = modules.detectViolations().messages.filterNot { it.startsWith("Cycle detected") }
        assertTrue(nonCycle.isEmpty(), nonCycle.joinToString("\n"))
    }
}
