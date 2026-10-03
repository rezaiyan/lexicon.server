package com.alirezaiyan.vokab.server.architecture

import com.alirezaiyan.vokab.server.presentation.controller.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.security.AuthUser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.io.File

/**
 * The API contract as a reviewed artifact: the OpenAPI spec rendered from the controllers must match
 * the committed `openapi.json`, so client-facing changes (paths, DTO fields, nullability) show up in
 * the diff and fail CI when unintended. After an intended change, regenerate it:
 * `./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi=true`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class OpenApiContractTest {

    @Autowired lateinit var mockMvc: MockMvc

    private val specFile = File("openapi.json")

    private val canonical = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    @Test
    fun `the rendered API spec matches the committed openapi json`() {
        val body = mockMvc.perform(
            get("/v3/api-docs").with(authentication(UsernamePasswordAuthenticationToken(AuthUser(0), null, emptyList())))
        ).andReturn().response.contentAsString
        val spec = canonical.readTree(body) as ObjectNode
        spec.remove("servers") // the request's host, not part of the contract
        val rendered = canonical.writeValueAsString(canonical.treeToValue(spec, Map::class.java)) + "\n"

        if (System.getProperty("openapi.update") == "true") {
            specFile.writeText(rendered)
            return
        }

        assertTrue(specFile.isFile, "openapi.json missing; generate it with -PupdateOpenApi=true")
        assertTrue(
            specFile.readText() == rendered,
            "The API contract changed. If intended, regenerate openapi.json with " +
                "`./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi=true` and commit it.",
        )
    }
}
