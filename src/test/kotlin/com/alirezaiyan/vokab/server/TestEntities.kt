package com.alirezaiyan.vokab.server

import com.alirezaiyan.vokab.server.domain.entity.JpaEntity

/**
 * Assigns a database id the way Hibernate does on persist (a field write on the same instance).
 * For fakes of `repository.save(newEntity)`.
 */
fun <T : JpaEntity<Long>> T.withAssignedId(id: Long): T = apply {
    val field = generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOf { cls -> cls.declaredFields.firstOrNull { it.name == "id" } }
    field.isAccessible = true
    field.set(this, id)
}
