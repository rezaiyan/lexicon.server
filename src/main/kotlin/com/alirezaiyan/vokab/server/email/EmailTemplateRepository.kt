package com.alirezaiyan.vokab.server.email

import org.springframework.data.jpa.repository.JpaRepository

interface EmailTemplateRepository : JpaRepository<EmailTemplate, String> {
    fun findByIdAndActiveTrue(id: String): EmailTemplate?
    fun findByCategoryAndActiveTrue(category: String): List<EmailTemplate>
}
