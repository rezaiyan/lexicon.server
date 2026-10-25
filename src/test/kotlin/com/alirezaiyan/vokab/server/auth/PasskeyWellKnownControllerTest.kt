package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.PasskeyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasskeyWellKnownControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    private val debugKey = "C9:64:0F:6A:68:90:AF:6C:26:49:DC:BB:AB:94:79:23:93:5C:FC:10:8B:DF:03:20:78:04:60:5C:5B:79:49:03"

    private fun controller(config: PasskeyConfig) =
        PasskeyWellKnownController(AppProperties(passkey = config))

    @Test
    fun `assetlinks lets the Android app use the passkeys with every signing key`() {
        val links = controller(
            PasskeyConfig(androidPackage = "com.example.app", androidCertFingerprints = "$debugKey, AA:BB"),
        ).assetLinks()

        val target = links.single()["target"] as Map<*, *>
        assertEquals(listOf("delegate_permission/common.get_login_creds"), links.single()["relation"])
        assertEquals("android_app", target["namespace"])
        assertEquals("com.example.app", target["package_name"])
        assertEquals(listOf(debugKey, "AA:BB"), target["sha256_cert_fingerprints"])
    }

    @Test
    fun `apple-app-site-association lists the iOS apps for webcredentials`() {
        val aasa = controller(PasskeyConfig(iosAppIds = "TEAM.com.example.app")).appleAppSiteAssociation()

        assertEquals(mapOf("apps" to listOf("TEAM.com.example.app")), aasa["webcredentials"])
    }

    @Test
    fun `origins are the RP ID over https plus one apk-key-hash per Android signing key`() {
        val config = PasskeyConfig(rpId = "lexicon.example.com", androidCertFingerprints = debugKey, origins = "")

        assertEquals(
            setOf(
                "https://lexicon.example.com",
                "android:apk-key-hash:yWQPamiQr2wmSdy7q5R5I5Nc_BCL3wMgeARgXFt5SQM",
            ),
            config.originSet,
        )
    }

    @Test
    fun `a fingerprint that is not a SHA-256 is rejected`() {
        assertThrows<IllegalArgumentException> { PasskeyConfig(androidCertFingerprints = "AA:BB").originSet }
    }

    @Test
    fun `both files are public JSON`() {
        listOf("/.well-known/assetlinks.json", "/.well-known/apple-app-site-association").forEach { path ->
            mockMvc.perform(get(path))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        }
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
            .andExpect(jsonPath("$.webcredentials.apps[0]").value("VFCFJC7Y5J.com.alirezaiyan.vokab"))
    }
}
