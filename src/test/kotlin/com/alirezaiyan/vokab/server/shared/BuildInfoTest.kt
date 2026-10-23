package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** Health reports BuildProperties.version; without build-info it silently fell back to "development". */
@SpringBootTest
@ActiveProfiles("test")
class BuildInfoTest {

    @Autowired(required = false) var buildProperties: BuildProperties? = null

    @Test
    fun `build info is generated so health reports the real version`() {
        val version = buildProperties?.version

        assertNotNull(version, "META-INF/build-info.properties missing — springBoot { buildInfo() } removed?")
        assertNotEquals("development", version)
    }
}
