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
import java.time.LocalDate

/** Where a rescue's words and copy came from. */
enum class RescueSource {
    /** AI picked the words and one of its copy candidates passed the linter. */
    AI,
    /** AI picks were valid, every AI copy candidate failed the linter: template copy. */
    AI_PICKS_TEMPLATE_COPY,
    /** AI unavailable or its picks invalid: deterministic picks and template copy. */
    FALLBACK,
}

enum class RescueStatus {
    PLANNED, HOLDOUT, SENT, COMPLETED, EXPIRED;

    companion object {
        /** Plans that use up the learner's one rescue for the current lapse. */
        val CONSUMES_LAPSE: Set<RescueStatus> = setOf(HOLDOUT, SENT, COMPLETED)
    }
}

/**
 * One learner's comeback rescue for one day: the ordered session words, the push copy and the
 * session outros, planned nightly by [ComebackRescuePlanner].
 */
@Entity
@Table(name = "comeback_rescue")
@Suppress("LongParameterList") // one constructor parameter per column
class ComebackRescue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "planned_for", nullable = false)
    val plannedFor: LocalDate,

    /** JSON array in session order; read through [wordIdList]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "word_ids", columnDefinition = "jsonb", nullable = false)
    val wordIds: String,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false)
    val body: String,

    @Column(nullable = false)
    val angle: String,

    @Column(name = "outro_win")
    val outroWin: String? = null,

    @Column(name = "outro_soft")
    val outroSoft: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val candidates: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "picks_reasoning", columnDefinition = "jsonb")
    val picksReasoning: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: RescueSource,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RescueStatus,

    @Column(name = "notification_log_id")
    var notificationLogId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,
) : JpaEntity<Long>() {

    val wordIdList: List<Long>
        get() = wordIds.removeSurrounding("[", "]").split(",").mapNotNull { it.trim().toLongOrNull() }

    override fun toString(): String =
        "ComebackRescue(id=$id, userId=$userId, plannedFor=$plannedFor, status=$status, source=$source)"

    companion object {
        fun encodeWordIds(ids: List<Long>): String = ids.joinToString(",", "[", "]")
    }
}
