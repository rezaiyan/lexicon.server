package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.ProcessedWebhookEvent
import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedWebhookEventRepository : JpaRepository<ProcessedWebhookEvent, String>
