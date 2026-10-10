package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Suppress("LongParameterList") // one constructor parameter per column
@Entity
@Table(
    name = "listening_sessions",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["user_id", "client_session_id"])
    ]
)
class ListeningSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "client_session_id", nullable = false)
    val clientSessionId: String,

    @Column(nullable = false)
    val source: String,

    @Column(name = "source_detail")
    val sourceDetail: String? = null,

    @Column(name = "word_order", nullable = false)
    val wordOrder: String,

    @Column(name = "repeat_count", nullable = false)
    val repeatCount: Int = 1,

    @Column(name = "pause_ms", nullable = false)
    val pauseMs: Long = 0,

    @Column(name = "speech_rate", nullable = false)
    val speechRate: Float = 1.0f,

    @Column(name = "planned_words", nullable = false)
    val plannedWords: Int = 0,

    @Column(name = "words_heard", nullable = false)
    val wordsHeard: Int = 0,

    @Column(name = "words_skipped", nullable = false)
    val wordsSkipped: Int = 0,

    @Column(name = "pause_count", nullable = false)
    val pauseCount: Int = 0,

    @Column(name = "listening_ms", nullable = false)
    val listeningMs: Long = 0,

    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long = 0,

    @Column(name = "completed_normally", nullable = false)
    val completedNormally: Boolean = false,

    @Column(name = "started_at", nullable = false)
    val startedAt: Long,

    @Column(name = "ended_at", nullable = false)
    val endedAt: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : JpaEntity<Long>() {
    override fun toString(): String =
        "ListeningSession(id=$id, clientSessionId='$clientSessionId', wordsHeard=$wordsHeard, startedAt=$startedAt)"
}

@Entity
@Table(name = "listening_session_words")
class ListeningSessionWord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: ListeningSession,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "word_id", nullable = false)
    val wordId: Long,

    @Column(name = "source_language", nullable = false)
    val sourceLanguage: String,

    @Column(name = "target_language", nullable = false)
    val targetLanguage: String,

    @Column(name = "heard_at", nullable = false)
    val heardAt: Long,
) : JpaEntity<Long>()
