package com.alirezaiyan.vokab.server.email

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface EmailLogRepository : JpaRepository<EmailLog, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<EmailLog>
    fun existsByUserIdAndTemplateIdAndCreatedAtAfter(userId: Long, templateId: String, after: Instant): Boolean
}
