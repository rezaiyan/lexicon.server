package com.alirezaiyan.vokab.server.shared

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Injectable time source, so time-dependent checks (e.g. webhook replay windows) are testable. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
