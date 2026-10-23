package com.alirezaiyan.vokab.server.analytics

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AppEventRepository : JpaRepository<AppEvent, Long>
