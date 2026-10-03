package com.alirezaiyan.vokab.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Wednesday 2026-06-17 10:00 UTC — a fixed "now" so date/time-dependent tests are deterministic. */
val TEST_NOW: Instant = Instant.parse("2026-06-17T10:00:00Z")
val TEST_TODAY: LocalDate = LocalDate.ofInstant(TEST_NOW, ZoneOffset.UTC)

fun fixedClock(at: Instant = TEST_NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

/** UTC clock tests can move forward, e.g. to cross a cache TTL. */
class MutableClock(private var now: Instant = TEST_NOW) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun set(instant: Instant) {
        now = instant
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
