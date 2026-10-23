package com.alirezaiyan.vokab.server.admin

import com.alirezaiyan.vokab.server.fixedClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppConfigServiceTest {

    private val appConfigRepository: AppConfigRepository = mockk()
    private val appConfigHistoryRepository: AppConfigHistoryRepository = mockk()
    private lateinit var service: AppConfigService

    @BeforeEach
    fun setUp() {
        service = AppConfigService(appConfigRepository, appConfigHistoryRepository, clock = fixedClock())
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun `get returns value from repository on cache miss`() {
        val config = testConfig(namespace = "ns", key = "k", value = "hello")
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns config

        val result = service.get("ns", "k")

        assertEquals("hello", result)
    }

    @Test
    fun `get returns null when config not found in repository`() {
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "missing") } returns null

        val result = service.get("ns", "missing")

        assertNull(result)
    }

    @Test
    fun `get returns cached value on second call without hitting repository`() {
        val config = testConfig(namespace = "ns", key = "k", value = "cached")
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns config

        service.get("ns", "k")          // populates cache
        val result = service.get("ns", "k")  // should hit cache

        assertEquals("cached", result)
        verify(exactly = 1) { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") }
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    fun `find returns config when present`() {
        val config = testConfig(namespace = "ns", key = "k")
        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config

        val result = service.find("ns", "k")

        assertEquals(config, result)
    }

    @Test
    fun `find returns null when config not found`() {
        every { appConfigRepository.findByNamespaceAndKey("ns", "absent") } returns null

        assertNull(service.find("ns", "absent"))
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list returns all configs ordered by namespace and key`() {
        val configs = listOf(testConfig(namespace = "a", key = "1"), testConfig(namespace = "a", key = "2"))
        every { appConfigRepository.findAllByOrderByNamespaceAscKeyAsc() } returns configs

        val result = service.list()

        assertEquals(configs, result)
    }

    // ── history ───────────────────────────────────────────────────────────────

    @Test
    fun `history returns history entries for given namespace and key`() {
        val historyEntries = listOf(
            AppConfigHistory(namespace = "ns", key = "k", oldValue = "old", newValue = "new")
        )
        every { appConfigHistoryRepository.findByNamespaceAndKeyOrderByChangedAtDesc("ns", "k") } returns historyEntries

        val result = service.history("ns", "k")

        assertEquals(historyEntries, result)
    }

    // ── set ───────────────────────────────────────────────────────────────────

    @Test
    fun `set updates config value, records history, and clears cache`() {
        val config = testConfig(id = 1L, namespace = "ns", key = "k", value = "old")
        val updatedConfig = testConfig(id = 1L, namespace = "ns", key = "k", value = "new")
        val historySlot = slot<AppConfigHistory>()

        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config
        every { appConfigRepository.save(any()) } returns updatedConfig
        every { appConfigHistoryRepository.save(capture(historySlot)) } answers { firstArg() }

        // Prime the cache first
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns config
        service.get("ns", "k")

        val result = service.set("ns", "k", "new", "admin")

        assertEquals("new", result.value)
        assertEquals("old", historySlot.captured.oldValue)
        assertEquals("new", historySlot.captured.newValue)
        assertEquals("admin", historySlot.captured.changedBy)

        // Cache must be cleared — next get should hit repository again
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns updatedConfig
        val afterSet = service.get("ns", "k")
        assertEquals("new", afterSet)
        verify(exactly = 2) { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") }
    }

    @Test
    fun `set throws NoSuchElementException when config not found`() {
        every { appConfigRepository.findByNamespaceAndKey("ns", "absent") } returns null

        assertThrows<NoSuchElementException> {
            service.set("ns", "absent", "value", null)
        }
    }

    // ── addListItem ───────────────────────────────────────────────────────────

    @Test
    fun `addListItem appends new item to existing comma-separated list`() {
        val config = testConfig(namespace = "ns", key = "k", value = "a,b")
        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns config
        every { appConfigRepository.save(any()) } answers { firstArg() }
        every { appConfigHistoryRepository.save(any()) } answers { firstArg() }

        service.addListItem("ns", "k", "c", null)

        verify { appConfigRepository.save(match { it.value == "a,b,c" }) }
    }

    @Test
    fun `addListItem throws when item already exists in list`() {
        val config = testConfig(namespace = "ns", key = "k", value = "a,b,c")
        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config

        assertThrows<IllegalArgumentException> {
            service.addListItem("ns", "k", "b", null)
        }
    }

    @Test
    fun `addListItem throws NoSuchElementException when config not found`() {
        every { appConfigRepository.findByNamespaceAndKey("ns", "absent") } returns null

        assertThrows<NoSuchElementException> {
            service.addListItem("ns", "absent", "x", null)
        }
    }

    // ── removeListItem ────────────────────────────────────────────────────────

    @Test
    fun `removeListItem removes existing item from comma-separated list`() {
        val config = testConfig(namespace = "ns", key = "k", value = "a,b,c")
        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("ns", "k") } returns config
        every { appConfigRepository.save(any()) } answers { firstArg() }
        every { appConfigHistoryRepository.save(any()) } answers { firstArg() }

        service.removeListItem("ns", "k", "b", null)

        verify { appConfigRepository.save(match { it.value == "a,c" }) }
    }

    @Test
    fun `removeListItem throws when item is not in list`() {
        val config = testConfig(namespace = "ns", key = "k", value = "a,b")
        every { appConfigRepository.findByNamespaceAndKey("ns", "k") } returns config

        assertThrows<IllegalArgumentException> {
            service.removeListItem("ns", "k", "z", null)
        }
    }

    @Test
    fun `removeListItem throws NoSuchElementException when config not found`() {
        every { appConfigRepository.findByNamespaceAndKey("ns", "absent") } returns null

        assertThrows<NoSuchElementException> {
            service.removeListItem("ns", "absent", "x", null)
        }
    }

    // ── getTestEmails ─────────────────────────────────────────────────────────

    @Test
    fun `getTestEmails parses comma-separated emails into a set`() {
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("testing", "test_emails") } returns
            testConfig(namespace = "testing", key = "test_emails", value = " a@b.com , c@d.com ")

        val result = service.getTestEmails()

        assertEquals(setOf("a@b.com", "c@d.com"), result)
    }

    @Test
    fun `getTestEmails returns empty set when config is absent`() {
        every { appConfigRepository.findByNamespaceAndKeyAndEnabledTrue("testing", "test_emails") } returns null

        val result = service.getTestEmails()

        assertTrue(result.isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun testConfig(
        id: Long? = null,
        namespace: String = "ns",
        key: String = "k",
        value: String? = "value",
        enabled: Boolean = true,
    ) = AppConfig(
        id = id,
        namespace = namespace,
        key = key,
        value = value,
        enabled = enabled,
    )
}
