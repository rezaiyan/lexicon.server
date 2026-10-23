package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.AddressNotFoundException
import com.maxmind.geoip2.model.CountryResponse
import com.maxmind.geoip2.record.Country
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.util.ReflectionTestUtils
import java.net.InetAddress
import java.nio.file.Path
import java.util.Optional

class GeoLocationServiceTest {

    private val userRepository: UserRepository = mockk()
    private lateinit var service: GeoLocationService

    @BeforeEach
    fun setUp() {
        // @PostConstruct is not invoked in unit tests — reader stays null by default
        service = GeoLocationService(AppProperties(), userRepository)
    }

    // ── resolveCountry ────────────────────────────────────────────────────────

    @Test
    fun `resolveCountry returns null when database reader is not initialized`() {
        // No DatabaseReader injected → reader is null
        assertNull(service.resolveCountry("1.2.3.4"))
    }

    @Test
    fun `resolveCountry returns ISO country code for valid public IP`() {
        injectReader(isoCode = "US")

        assertEquals("US", service.resolveCountry("8.8.8.8"))
    }

    @Test
    fun `resolveCountry returns null for private IP (AddressNotFoundException)`() {
        val mockReader = mockk<DatabaseReader>()
        every { mockReader.country(any<InetAddress>()) } throws AddressNotFoundException("private ip")
        ReflectionTestUtils.setField(service, "reader", mockReader)

        assertNull(service.resolveCountry("127.0.0.1"))
    }

    @Test
    fun `resolveCountry absorbs unexpected exceptions and returns null`() {
        val mockReader = mockk<DatabaseReader>()
        every { mockReader.country(any<InetAddress>()) } throws RuntimeException("db error")
        ReflectionTestUtils.setField(service, "reader", mockReader)

        assertNull(service.resolveCountry("1.2.3.4"))
    }

    // ── updateUserCountry ─────────────────────────────────────────────────────

    @Test
    fun `updateUserCountry does nothing when country cannot be resolved`() {
        // reader is null → resolveCountry returns null → early return before any DB call
        service.updateUserCountry(1L, "127.0.0.1", isNewUser = false)

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `updateUserCountry does nothing when user is not found`() {
        injectReader(isoCode = "DE")
        every { userRepository.findById(99L) } returns Optional.empty()

        service.updateUserCountry(99L, "1.2.3.4", isNewUser = false)

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `updateUserCountry sets both signup and last login country for new user`() {
        injectReader(isoCode = "DE")
        val user = testUser(id = 1L, signupCountry = null)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        service.updateUserCountry(1L, "1.2.3.4", isNewUser = true)

        verify { userRepository.save(match { it.lastLoginCountry == "DE" && it.signupCountry == "DE" }) }
    }

    @Test
    fun `updateUserCountry preserves existing signupCountry for returning user`() {
        injectReader(isoCode = "FR")
        val user = testUser(id = 2L, signupCountry = "US")
        every { userRepository.findById(2L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        service.updateUserCountry(2L, "1.2.3.4", isNewUser = false)

        verify { userRepository.save(match { it.lastLoginCountry == "FR" && it.signupCountry == "US" }) }
    }

    @Test
    fun `updateUserCountry sets signupCountry when existing user has no prior signup country`() {
        injectReader(isoCode = "JP")
        val user = testUser(id = 3L, signupCountry = null)
        every { userRepository.findById(3L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        service.updateUserCountry(3L, "1.2.3.4", isNewUser = false)

        verify { userRepository.save(match { it.lastLoginCountry == "JP" && it.signupCountry == "JP" }) }
    }

    // ── init / destroy ───────────────────────────────────────────────────────

    @Test
    fun `init with blank database path leaves reader null`() {
        // Default AppProperties has empty databasePath
        service.init()
        assertNull(service.resolveCountry("8.8.8.8"))
    }

    @Test
    fun `init with non-existent file leaves reader null`() {
        val props = AppProperties()
        props.geolocation.databasePath = "/nonexistent/path/fake.mmdb"
        val svc = GeoLocationService(props, userRepository)
        svc.init()
        assertNull(svc.resolveCountry("8.8.8.8"))
    }

    @Test
    fun `init with invalid database file absorbs InvalidDatabaseException`(@TempDir tempDir: Path) {
        val invalidFile = tempDir.resolve("fake.mmdb").toFile()
        invalidFile.writeText("not a real mmdb file")
        val props = AppProperties()
        props.geolocation.databasePath = invalidFile.absolutePath
        val svc = GeoLocationService(props, userRepository)
        svc.init() // InvalidDatabaseException must be caught — no throw
        assertNull(svc.resolveCountry("8.8.8.8"))
    }

    @Test
    fun `destroy does nothing when reader is null`() {
        service.destroy() // must not throw
    }

    @Test
    fun `destroy closes reader when reader is initialized`() {
        val mockReader = mockk<DatabaseReader>(relaxed = true)
        ReflectionTestUtils.setField(service, "reader", mockReader)
        service.destroy()
        verify { mockReader.close() }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun injectReader(isoCode: String) {
        val mockReader = mockk<DatabaseReader>()
        val mockResponse = mockk<CountryResponse>()
        val mockCountry = mockk<Country>()
        every { mockCountry.isoCode } returns isoCode
        every { mockResponse.country } returns mockCountry
        every { mockReader.country(any<InetAddress>()) } returns mockResponse
        ReflectionTestUtils.setField(service, "reader", mockReader)
    }

    private fun testUser(id: Long, signupCountry: String? = null) = User(
        id = id,
        email = "test@example.com",
        name = "Test User",
        signupCountry = signupCountry,
    )
}
