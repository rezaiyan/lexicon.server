package com.alirezaiyan.vokab.server.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Production code must read time from the injected [java.time.Clock] bean so it is testable and
 * always UTC. `now()` without a clock silently uses the JVM default zone and the real time.
 */
class WallClockGuardTest {

    private val sourceRoot = File("src/main/kotlin")

    private val wallClockRead = Regex(
        """\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth)\.now\((?!clock\))|""" +
            """System\.currentTimeMillis\(\)|\bDate\(\)"""
    )

    // Entity/event field defaults can't take an injected clock; the entity refactor revisits them.
    private fun isExempt(file: File): Boolean {
        val text = file.readText()
        return Regex("""^@Entity\b""", RegexOption.MULTILINE).containsMatchIn(text) || file.name == "DomainEvent.kt"
    }

    @Test
    fun `production code reads time only through the injected Clock`() {
        assertTrue(sourceRoot.isDirectory, "run from the project root")

        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot(::isExempt)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (wallClockRead.containsMatchIn(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertTrue(offenders.isEmpty(), "Use now(clock) / clock.millis() instead:\n" + offenders.joinToString("\n"))
    }
}
