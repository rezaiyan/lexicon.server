package com.alirezaiyan.vokab.server.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Layer rules for production code: Controller → Service → Repository, entities stay inside the
 * service layer, and the API never exposes a JPA entity.
 */
class ArchitectureTest {

    private val production = Konsist.scopeFromProduction()

    // Matched by simple name: withAnnotationOf() resolves through imports and misses `import jakarta.persistence.*`
    private fun annotatedWith(name: String): List<KoClassDeclaration> =
        production.classes().filter { cls -> cls.annotations.any { it.name == name } }

    private val entities get() = annotatedWith("Entity")
    private val controllers get() = annotatedWith("RestController")

    private val controllerFiles get() = production.files.filter { it.packagee?.name?.endsWith(".presentation.controller") == true }

    @Test
    fun `rules see every entity and controller`() {
        // Guards against the rules below passing vacuously when a selector stops matching
        val sourceCount = { annotation: String ->
            production.files.sumOf { file -> Regex("""^@$annotation\b""", RegexOption.MULTILINE).findAll(file.text).count() }
        }
        assertEquals(sourceCount("Entity"), entities.size)
        assertEquals(sourceCount("RestController"), controllers.size)
    }

    @Test
    fun `controllers go through services, never repositories`() {
        controllerFiles.assertFalse { file ->
            file.imports.any { it.name.contains(".domain.repository.") }
        }
    }

    @Test
    fun `services and the domain never depend on controllers`() {
        production.files
            .filterNot { it.packagee?.name?.endsWith(".presentation.controller") == true }
            .assertFalse { file -> file.imports.any { it.name.contains(".presentation.controller.") } }
    }

    @Test
    fun `entities do not depend on services, repositories or the API layer`() {
        production.files
            .filter { it.packagee?.name?.endsWith(".domain.entity") == true }
            .assertFalse { file ->
                file.imports.any { import ->
                    listOf(".service.", ".domain.repository.", ".presentation.").any { import.name.contains(it) }
                }
            }
    }

    @Test
    fun `controller endpoints never return or accept JPA entities`() {
        val entityNames = entities.map { it.name }
        val entityType = Regex("""\b(${entityNames.joinToString("|")})\b""")

        controllers
            .flatMap { it.functions() }
            .filter { function -> function.annotations.any { it.name.endsWith("Mapping") } }
            .assertFalse { function ->
                val signature = listOfNotNull(function.returnType?.text) + function.parameters.map { it.type.text }
                signature.any { entityType.containsMatchIn(it) }
            }
    }

    @Test
    fun `entities are not data classes`() {
        // equals/hashCode/toString over all fields break with lazy associations and generated ids
        entities.assertFalse { it.hasDataModifier }
    }

    @Test
    fun `controllers are suffixed Controller`() {
        controllers.assertTrue { it.name.endsWith("Controller") }
    }

    @Test
    fun `production code has no non-null assertions`() {
        production.files.assertFalse { it.text.contains("!!") }
    }
}
