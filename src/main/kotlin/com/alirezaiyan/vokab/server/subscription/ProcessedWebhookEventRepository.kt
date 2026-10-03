package com.alirezaiyan.vokab.server.subscription

import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedWebhookEventRepository : JpaRepository<ProcessedWebhookEvent, String>
