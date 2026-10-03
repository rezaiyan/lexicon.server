package com.alirezaiyan.vokab.server.domain.entity

import org.hibernate.Hibernate

/**
 * Identity for JPA entities: two instances are equal when they are the same entity type with the
 * same persisted id.
 *
 * - `hashCode` is constant per type so it does not change when the id is assigned on persist
 *   (an entity added to a `Set` before saving stays findable).
 * - Unsaved entities are equal only to themselves.
 * - `Hibernate.getClass` unwraps lazy proxies so a proxy equals its loaded entity.
 * - `toString` never touches lazy associations; subclasses may add plain columns.
 */
abstract class JpaEntity<ID : Any> {

    abstract val id: ID?

    /** True until the database has assigned an id. Override for entities with a non-null placeholder id. */
    protected open fun isTransient(): Boolean = id == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JpaEntity<*>) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        if (isTransient() || other.isTransient()) return false
        return id == other.id
    }

    override fun hashCode(): Int = Hibernate.getClass(this).hashCode()

    override fun toString(): String = "${Hibernate.getClass(this).simpleName}(id=$id)"
}
