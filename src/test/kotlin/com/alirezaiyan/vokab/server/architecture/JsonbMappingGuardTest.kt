package com.alirezaiyan.vokab.server.architecture

import com.alirezaiyan.vokab.server.Application
import jakarta.persistence.Column
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * `columnDefinition = "jsonb"` only shapes DDL; without a JSON JDBC type Hibernate binds the String
 * as varchar and PostgreSQL rejects the insert ("column is of type jsonb but expression is of type
 * character varying"). Integration tests on PostgreSQL only catch it for entities they happen to insert;
 * this check covers every entity.
 */
class JsonbMappingGuardTest {

    @Test
    fun `every jsonb column is bound as JSON`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(Entity::class.java))
        }
        val entities = scanner.findCandidateComponents(Application::class.java.packageName)
            .map { Class.forName(it.beanClassName) }
        assertTrue(entities.isNotEmpty())

        val jsonbFields = entities.flatMap { cls -> cls.declaredFields.map { cls to it } }
            .filter { (_, field) -> field.getAnnotation(Column::class.java)?.columnDefinition.equals("jsonb", ignoreCase = true) }
        assertTrue(jsonbFields.isNotEmpty())

        val unbound = jsonbFields
            .filter { (_, field) -> field.getAnnotation(JdbcTypeCode::class.java)?.value != SqlTypes.JSON }
            .map { (cls, field) -> "${cls.simpleName}.${field.name}" }

        assertTrue(unbound.isEmpty(), "Add @JdbcTypeCode(SqlTypes.JSON) to: $unbound")
    }
}
