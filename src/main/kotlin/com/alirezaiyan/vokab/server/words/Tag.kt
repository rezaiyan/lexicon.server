package com.alirezaiyan.vokab.server.words

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(
    name = "tags",
    indexes = [Index(name = "idx_tags_user_id", columnList = "user_id")],
    uniqueConstraints = [UniqueConstraint(name = "uq_tags_user_name", columnNames = ["user_id", "name"])]
)
class Tag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User? = null,

    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    val words: MutableSet<Word> = mutableSetOf(),
) : JpaEntity<Long>()
