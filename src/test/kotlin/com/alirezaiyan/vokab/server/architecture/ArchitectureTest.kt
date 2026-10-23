package com.alirezaiyan.vokab.server.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Layer rules inside each feature package: Controller → Service → Repository, entities depend on no
 * layer above them, and the API never exposes a JPA entity. Features share a package, so the rules
 * look at what a class is wired with (constructor dependencies by type), not at imports. Boundaries
 * between features are checked by [ModularityTest].
 */
class ArchitectureTest {

    private val production = Konsist.scopeFromProduction()

    // Matched by simple name: withAnnotationOf() resolves through imports and misses `import jakarta.persistence.*`
    private fun annotatedWith(name: String): List<KoClassDeclaration> =
        production.classes().filter { cls -> cls.annotations.any { it.name == name } }

    private val entities get() = annotatedWith("Entity")
    private val controllers get() = annotatedWith("RestController")

    private fun KoClassDeclaration.dependencyTypes(): List<String> =
        primaryConstructor?.parameters?.map { it.type.text }.orEmpty()

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
        controllers.assertFalse { controller -> controller.dependencyTypes().any { it.endsWith("Repository") } }
    }

    @Test
    fun `nothing depends on a controller`() {
        production.classes().assertFalse { cls -> cls.dependencyTypes().any { it.endsWith("Controller") } }
    }

    @Test
    fun `entities do not depend on services, repositories or the API layer`() {
        val upperLayer = Regex("""(Service|Repository|Controller|Dto|Request|Response)\b""")
        entities.assertFalse { entity ->
            val types = entity.dependencyTypes() + entity.properties().mapNotNull { it.type?.text }
            types.any { upperLayer.containsMatchIn(it) }
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
