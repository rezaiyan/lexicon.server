package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.JpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Rescue copy drafted for a real learner from the admin page so Ali can taste-test it. Never sent,
 * and stored apart from [ComebackRescue] so it can't block or count as a real plan.
 */
@Entity
@Table(name = "rescue_preview")
@Suppress("LongParameterList") // one constructor parameter per column
class RescuePreview(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "prompt_version", nullable = false)
    val promptVersion: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: RescueSource,

    @Column(nullable = false)
    val angle: String,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false)
    val body: String,

    @Column(name = "outro_win")
    val outroWin: String? = null,

    @Column(name = "outro_soft")
    val outroSoft: String? = null,

    /** JSON array in session order, as in [ComebackRescue.wordIds]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "word_ids", columnDefinition = "jsonb", nullable = false)
    val wordIds: String,

    /** Every AI copy candidate with its linter violations; null for FALLBACK drafts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val candidates: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "picks_reasoning", columnDefinition = "jsonb")
    val picksReasoning: String? = null,
) : JpaEntity<Long>() {
    override fun toString(): String = "RescuePreview(id=$id, userId=$userId, source=$source)"
}

enum class CopyRating { UP, DOWN }

/** Ali's verdict on one copy candidate of a [RescuePreview]; one per candidate. */
@Entity
@Table(name = "rescue_copy_rating")
class RescueCopyRating(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "preview_id", nullable = false)
    val previewId: Long,

    @Column(name = "candidate_index", nullable = false)
    val candidateIndex: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var rating: CopyRating,

    @Column
    var note: String? = null,

    @Column(name = "rated_at", nullable = false)
    var ratedAt: Instant = Instant.now(),
) : JpaEntity<Long>() {
    override fun toString(): String =
        "RescueCopyRating(previewId=$previewId, candidateIndex=$candidateIndex, rating=$rating)"
}
