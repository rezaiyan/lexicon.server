package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*

@Entity
@Table(name = "review_events")
class ReviewEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: StudySession,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "word_id", nullable = false)
    val wordId: Long,

    @Column(name = "word_text", nullable = false)
    val wordText: String = "",

    @Column(name = "word_translation", nullable = false)
    val wordTranslation: String = "",

    @Column(name = "source_language", nullable = false)
    val sourceLanguage: String = "",

    @Column(name = "target_language", nullable = false)
    val targetLanguage: String = "",

    @Column(nullable = false)
    val rating: Int,

    @Column(name = "previous_level", nullable = false)
    val previousLevel: Int,

    @Column(name = "new_level", nullable = false)
    val newLevel: Int,

    @Column(name = "response_time_ms", nullable = false)
    val responseTimeMs: Long = 0,

    @Column(name = "reviewed_at", nullable = false)
    val reviewedAt: Long,
) : JpaEntity<Long>() {
    override fun toString(): String {
        return "ReviewEvent(id=$id, wordId=$wordId, rating=$rating, previousLevel=$previousLevel, newLevel=$newLevel)"
    }
}
