package com.alirezaiyan.vokab.server.study

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User

/**
 * Tracks daily user activity for streak calculation
 */
@Entity
@Table(
    name = "daily_activities",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["user_id", "activity_date"])
    ],
    indexes = [
        Index(name = "idx_user_activity_date", columnList = "user_id,activity_date")
    ]
)
class DailyActivity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    
    @Column(name = "activity_date", nullable = false)
    val activityDate: LocalDate,
    
    @Column(name = "review_count", nullable = false)
    var reviewCount: Int = 1,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) : JpaEntity<Long>()


