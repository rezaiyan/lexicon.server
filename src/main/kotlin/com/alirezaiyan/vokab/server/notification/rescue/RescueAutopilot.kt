package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.JpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

/** The single autopilot row: whether rescue sends are paused, until when and why. */
@Entity
@Table(name = "rescue_autopilot")
class RescueAutopilot(
    @Id
    override val id: Short = SINGLETON_ID,

    /** Sends are paused before this day; null = running. */
    @Column(name = "paused_until")
    var pausedUntil: LocalDate? = null,

    @Column
    var reason: String? = null,

    /** Last pause or resume; opt-outs before it were already acted on. */
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.EPOCH,
) : JpaEntity<Short>() {

    fun pausedOn(day: LocalDate): Boolean = pausedUntil?.let { day.isBefore(it) } ?: false

    override fun toString(): String = "RescueAutopilot(pausedUntil=$pausedUntil, reason=$reason)"

    companion object {
        const val SINGLETON_ID: Short = 1
    }
}

interface RescueAutopilotRepository : JpaRepository<RescueAutopilot, Short>
