package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "notification_schedule")
class NotificationSchedule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Column(name = "optimal_send_hour", nullable = false)
    var optimalSendHour: Int = 18,

    @Column(name = "timezone_offset_hrs", nullable = false)
    var timezoneOffsetHrs: Int = 0,

    @Column(name = "data_confidence", nullable = false)
    var dataConfidence: Int = 0,

    @Column(name = "last_computed_at")
    var lastComputedAt: Instant? = null,

    @Column(name = "last_sent_date")
    var lastSentDate: LocalDate? = null,

    @Column(name = "last_sent_type")
    var lastSentType: String? = null,

    @Column(name = "consecutive_ignores", nullable = false)
    var consecutiveIgnores: Int = 0,

    @Column(name = "suppressed_until")
    var suppressedUntil: LocalDate? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_milestone_snapshot", columnDefinition = "jsonb")
    var lastMilestoneSnapshot: String? = null,

    // Engagement segment — computed nightly from notification_log history
    @Column(name = "engagement_segment", nullable = false)
    var engagementSegment: String = "WARM",

    // AI advisor fields — populated by NotificationAiAdvisor for COLD/DORMANT users
    @Column(name = "ai_action")
    var aiAction: String? = null,

    @Column(name = "ai_interval_days")
    var aiIntervalDays: Int? = null,

    @Column(name = "ai_content_hint")
    var aiContentHint: String? = null,

    @Column(name = "ai_decided_at")
    var aiDecidedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationSchedule) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = if (id != 0L) id.hashCode() else System.identityHashCode(this)

    override fun toString(): String =
        "NotificationSchedule(id=$id, userId=${user.id}, optimalSendHour=$optimalSendHour)"
}
