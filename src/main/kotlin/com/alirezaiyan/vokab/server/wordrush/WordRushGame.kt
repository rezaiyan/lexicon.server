package com.alirezaiyan.vokab.server.wordrush

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(
    name = "word_rush_games",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["user_id", "client_game_id"])
    ]
)
class WordRushGame(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "client_game_id", nullable = false)
    val clientGameId: String,

    @Column(nullable = false)
    val score: Int = 0,

    @Column(name = "total_questions", nullable = false)
    val totalQuestions: Int = 0,

    @Column(name = "correct_count", nullable = false)
    val correctCount: Int = 0,

    @Column(name = "best_streak", nullable = false)
    val bestStreak: Int = 0,

    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long = 0,

    @Column(name = "avg_response_ms", nullable = false)
    val avgResponseMs: Long = 0,

    @Column(nullable = false)
    val grade: String = "D",

    @Column(name = "lives_remaining", nullable = false)
    val livesRemaining: Int = 0,

    @Column(name = "completed_normally", nullable = false)
    val completedNormally: Boolean = false,

    @Column(name = "played_at", nullable = false)
    val playedAt: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : JpaEntity<Long>() {
    override fun toString(): String {
        return "WordRushGame(id=$id, clientGameId='$clientGameId', score=$score, playedAt=$playedAt)"
    }
}
