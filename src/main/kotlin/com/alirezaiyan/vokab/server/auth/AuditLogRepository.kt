package com.alirezaiyan.vokab.server.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<AuditLog>
    fun findByEventTypeOrderByCreatedAtDesc(eventType: AuditEventType): List<AuditLog>
}
