package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "study_sessions",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["user_id", "client_session_id"])
    ]
)
class StudySession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "client_session_id", nullable = false)
    val clientSessionId: String,

    @Column(name = "started_at", nullable = false)
    val startedAt: Long,

    @Column(name = "ended_at")
    val endedAt: Long? = null,

    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long = 0,

    @Column(name = "total_cards", nullable = false)
    val totalCards: Int = 0,

    @Column(name = "correct_count", nullable = false)
    val correctCount: Int = 0,

    @Column(name = "incorrect_count", nullable = false)
    val incorrectCount: Int = 0,

    @Column(name = "review_type", nullable = false)
    val reviewType: String = "REVIEW",

    @Column(name = "completed_normally", nullable = false)
    val completedNormally: Boolean = true,

    @Column(name = "source_language")
    val sourceLanguage: String? = null,

    @Column(name = "target_language")
    val targetLanguage: String? = null,

    @Column(name = "trigger_source", nullable = false)
    val triggerSource: String = "unknown",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : JpaEntity<Long>() {
    override fun toString(): String {
        return "StudySession(id=$id, clientSessionId='$clientSessionId', startedAt=$startedAt, totalCards=$totalCards)"
    }
}
