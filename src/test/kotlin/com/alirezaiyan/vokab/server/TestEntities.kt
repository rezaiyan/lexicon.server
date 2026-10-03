package com.alirezaiyan.vokab.server

import com.alirezaiyan.vokab.server.domain.entity.JpaEntity
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import io.mockk.every

/**
 * Stubs [UserRepository.getReferenceById] like JPA's proxy: the instance in [known] with that id (read
 * at call time, so tests can add users later), else a stand-in with just the id; entities compare by
 * id, so the stand-in still matches stubs on the test's user.
 */
fun UserRepository.answerReferences(known: Collection<User> = emptyList()): UserRepository = apply {
    every { getReferenceById(any()) } answers {
        val id = firstArg<Long>()
        known.firstOrNull { it.id == id } ?: User(id = id, email = "user$id@example.com", name = "User $id")
    }
}

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
