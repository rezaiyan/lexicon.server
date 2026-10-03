package com.alirezaiyan.vokab.server.words

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(name = "words", indexes = [
    Index(name = "idx_words_user_id", columnList = "user_id"),
    Index(name = "idx_words_level", columnList = "level")
])
class Word(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User? = null,

    @Column(nullable = false)
    var originalWord: String = "",

    @Column(nullable = false)
    var translation: String = "",

    @Column(columnDefinition = "TEXT")
    var description: String = "",

    @Column(length = 10)
    var sourceLanguage: String = "",

    @Column(length = 10)
    var targetLanguage: String = "",

    @Column(nullable = false)
    var level: Int = 0,

    @Column(nullable = false)
    var easeFactor: Float = 2.5f,

    @Column(name = "review_interval", nullable = false)
    var interval: Int = 0,

    @Column(nullable = false)
    var repetitions: Int = 0,

    @Column(nullable = false)
    var lastReviewDate: Long = 0L,

    @Column(nullable = false)
    var nextReviewDate: Long = 0L,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    var version: Long = 0,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "word_tags",
        joinColumns = [JoinColumn(name = "word_id")],
        inverseJoinColumns = [JoinColumn(name = "tag_id")]
    )
    var tags: MutableSet<Tag> = mutableSetOf(),
) : JpaEntity<Long>()
