package com.alirezaiyan.vokab.server.analytics.insightsscreen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeroBuilderTest {

    @Test
    fun `hero compares this week with last week`() {
        val facts = reviews("2026-06-16T09:00", 25, 20) + reviews("2026-06-09T09:00", 20, 10, firstWordId = 500)
        val hero = HeroBuilder.build(snapshot(reviews = facts, currentStreak = 3))

        assertEquals(MetricDto(25, 20), hero.reviews)
        assertEquals(MetricDto(80, 50), hero.accuracyPct)
        assertEquals(3, hero.currentStreak)
        assertEquals(7, hero.week.size)
        assertEquals("2026-06-15", hero.week.first().date)
        assertEquals(25, hero.week[1].reviews)
    }

    @Test
    fun `accuracy previous is null when last week is below the gate`() {
        val empty = HeroBuilder.build(snapshot(reviews = reviews("2026-06-16T09:00", 25, 20)))
        assertEquals(MetricDto(80, null), empty.accuracyPct)
        assertEquals(MetricDto(25, 0), empty.reviews)

        val thin = reviews("2026-06-16T09:00", 25, 20) + reviews("2026-06-09T09:00", 5, 5, firstWordId = 500)
        assertEquals(MetricDto(80, null), HeroBuilder.build(snapshot(reviews = thin)).accuracyPct)
    }

    @Test
    fun `accuracy is hidden below the sample gate`() {
        assertNull(HeroBuilder.build(snapshot(reviews = reviews("2026-06-16T09:00", 5, 5))).accuracyPct)
    }

    @Test
    fun `headline reflects the week`() {
        assertEquals("A fresh week to learn", HeroBuilder.build(snapshot()).headline)
        val best = reviews("2026-06-16T09:00", 30, 30) + reviews("2026-06-09T09:00", 10, 10, firstWordId = 500)
        assertEquals("Your best week in 12 weeks", HeroBuilder.build(snapshot(reviews = best)).headline)
    }

    @Test
    fun `new user with no earlier weeks gets a great start headline`() {
        val facts = reviews("2026-06-16T09:00", 5, 5)
        assertEquals("Great start to your week", HeroBuilder.build(snapshot(reviews = facts)).headline)
    }
}
