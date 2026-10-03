package com.alirezaiyan.vokab.server.email

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity

@Entity
@Table(name = "email_templates")
class EmailTemplate(
    @Id
    @Column(length = 100)
    override val id: String,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false, length = 500)
    val subject: String,

    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    val bodyHtml: String,

    @Column(name = "body_text", columnDefinition = "TEXT")
    val bodyText: String? = null,

    @Column(nullable = false, length = 50)
    val category: String,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now()
) : JpaEntity<String>()
