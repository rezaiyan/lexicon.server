package com.alirezaiyan.vokab.server.analytics.insightsscreen

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId

@Service
class InsightsScreenService(
    private val loader: LearnerSnapshotLoader,
    private val coachEngine: CoachEngine,
) {
    @Transactional(readOnly = true)
    fun build(userId: Long, zone: ZoneId): InsightsScreenResponse {
        val snapshot = loader.load(userId, zone)
        val (sections, locked) = SectionBuilders.build(snapshot)
        return InsightsScreenResponse(
            generatedAt = snapshot.now.toInstant().toString(),
            totalReviews = snapshot.totalReviewsAllTime,
            hero = HeroBuilder.build(snapshot),
            coach = coachEngine.cardsFor(snapshot),
            sections = sections,
            locked = locked,
        )
    }
}
