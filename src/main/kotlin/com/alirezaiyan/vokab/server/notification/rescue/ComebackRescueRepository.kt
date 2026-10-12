package com.alirezaiyan.vokab.server.notification.rescue

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

interface ComebackRescueRepository : JpaRepository<ComebackRescue, Long> {

    fun findByUserIdAndPlannedFor(userId: Long, plannedFor: LocalDate): ComebackRescue?

    fun existsByUserIdAndPlannedForAfterAndStatusIn(
        userId: Long,
        plannedFor: LocalDate,
        statuses: Collection<RescueStatus>,
    ): Boolean

    /** The learner's latest plans, newest first: last angle and recent copy for variety checks. */
    fun findTop3ByUserIdOrderByPlannedForDesc(userId: Long): List<ComebackRescue>

    @Transactional
    @Modifying
    @Query("UPDATE ComebackRescue r SET r.status = :expired WHERE r.status = :planned AND r.plannedFor < :today")
    fun expireStale(
        @Param("today") today: LocalDate,
        @Param("planned") planned: RescueStatus,
        @Param("expired") expired: RescueStatus,
    ): Int
}
