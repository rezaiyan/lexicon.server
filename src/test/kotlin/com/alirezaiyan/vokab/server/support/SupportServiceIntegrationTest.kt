package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.notification.NotificationCategory
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.requireId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/** Records relayed texts and hands out increasing Telegram message ids. */
class FakeSupportRelay : SupportRelay {
    val posted = mutableListOf<String>()
    val acknowledged = mutableListOf<Long>()
    var available = true
    private var nextId = 1000L

    override fun post(text: String): Long? {
        if (!available) return null
        posted += text
        return nextId++
    }

    override fun acknowledge(messageId: Long) {
        acknowledged += messageId
    }

    fun reset() {
        posted.clear()
        acknowledged.clear()
        available = true
        nextId = 1000L
    }
}

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SupportServiceIntegrationTest.RelayConfig::class)
@TestPropertySource(properties = ["app.notifications.admin.telegram.chat-id=$ADMIN_CHAT"])
class SupportServiceIntegrationTest {

    @TestConfiguration
    class RelayConfig {
        @Bean
        @Primary
        fun fakeSupportRelay() = FakeSupportRelay()
    }

    @Autowired lateinit var supportService: SupportService
    @Autowired lateinit var relay: FakeSupportRelay
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var jdbc: JdbcTemplate

    @MockitoBean lateinit var pushNotificationService: PushNotificationService

    private lateinit var user: User
    private val client = SupportClientInfo(platform = "Android", appVersion = "1.46.0")

    @BeforeAll
    fun setupUser() {
        user = testUserHelper.saveAndCommit(User(email = "support-user@gmail.com", name = "Sam Support"))
    }

    @AfterAll
    fun teardownUser() {
        clear()
        testUserHelper.deleteByEmail("support-user@gmail.com")
    }

    @BeforeEach
    fun setup() {
        clear()
        relay.reset()
        reset(pushNotificationService)
    }

    @Test
    fun `user message is stored and relayed with masked identity and device`() {
        val sent = supportService.sendUserMessage(user.requireId(), request("Hi, sync is broken"), client)

        assertEquals(SupportSender.USER, sent.sender)
        val relayed = relay.posted.single()
        assertTrue("Sam Support (su***@gmail.com)" in relayed, relayed)
        assertTrue("Android 1.46.0" in relayed, relayed)
        assertTrue("Hi, sync is broken" in relayed, relayed)
        assertTrue("support-user@gmail.com" !in relayed, "full email must never reach Telegram")
        assertEquals(1000L, jdbc.queryForObject("SELECT telegram_message_id FROM support_messages", Long::class.java))
    }

    @Test
    fun `retried send with same client id stores and relays once`() {
        supportService.sendUserMessage(user.requireId(), request("once", clientId = "c-1"), client)
        val retry = supportService.sendUserMessage(user.requireId(), request("once", clientId = "c-1"), client)

        assertEquals("c-1", retry.clientMessageId)
        assertEquals(1, count())
        assertEquals(1, relay.posted.size)
    }

    @Test
    fun `message is kept when the relay is down`() {
        relay.available = false

        supportService.sendUserMessage(user.requireId(), request("still saved"), client)

        assertEquals(1, count())
        assertEquals(
            null,
            jdbc.queryForObject("SELECT telegram_message_id FROM support_messages", Long::class.javaObjectType),
        )
    }

    @Test
    fun `telegram reply to a relayed message lands in the thread and pushes the user`() {
        supportService.sendUserMessage(user.requireId(), request("help"), client)

        supportService.handleTelegramUpdate(reply(messageId = 7, replyTo = 1000, text = "On it!"))

        val messages = supportService.getMessages(user.requireId(), afterId = 0).messages
        assertEquals(listOf(SupportSender.USER, SupportSender.ADMIN), messages.map { it.sender })
        assertEquals("On it!", messages.last().body)
        assertEquals(listOf(7L), relay.acknowledged)
        verify(pushNotificationService).sendNotificationToUser(
            user.requireId(), "Lexicon Support", "On it!", mapOf("type" to SUPPORT_REPLY_PUSH_TYPE), null, NotificationCategory.SYSTEM,
        )
    }

    @Test
    fun `retried telegram update is stored once`() {
        supportService.sendUserMessage(user.requireId(), request("help"), client)
        val update = reply(messageId = 7, replyTo = 1000, text = "On it!")

        supportService.handleTelegramUpdate(update)
        supportService.handleTelegramUpdate(update)

        assertEquals(2, count())
    }

    @Test
    fun `telegram messages from another chat, non-replies, or unknown targets are ignored`() {
        supportService.sendUserMessage(user.requireId(), request("help"), client)

        supportService.handleTelegramUpdate(reply(messageId = 7, replyTo = 1000, text = "x", chatId = 999))
        supportService.handleTelegramUpdate(reply(messageId = 8, replyTo = null, text = "x"))
        supportService.handleTelegramUpdate(reply(messageId = 9, replyTo = 5555, text = "x"))

        assertEquals(1, count())
        verifyNoInteractions(pushNotificationService)
    }

    @Test
    fun `unread count tracks admin replies until marked read`() {
        val thread = sendAndGetThreadId()
        supportService.replyFromDashboard(thread, AdminSupportReplyRequest("first"))
        supportService.replyFromDashboard(thread, AdminSupportReplyRequest("second"))

        assertEquals(2, supportService.getMessages(user.requireId(), 0).unreadCount)
        supportService.markRead(user.requireId())
        assertEquals(0, supportService.getMessages(user.requireId(), 0).unreadCount)
    }

    @Test
    fun `dashboard reply is mirrored to telegram and a reply to the mirror maps back`() {
        val thread = sendAndGetThreadId()

        supportService.replyFromDashboard(thread, AdminSupportReplyRequest("from dashboard"))
        supportService.handleTelegramUpdate(reply(messageId = 20, replyTo = 1001, text = "follow-up"))

        assertTrue(relay.posted.last().contains("from dashboard"))
        assertEquals(
            listOf("help", "from dashboard", "follow-up"),
            supportService.getMessages(user.requireId(), 0).messages.map { it.body },
        )
    }

    @Test
    fun `getMessages after id returns only newer messages`() {
        val first = supportService.sendUserMessage(user.requireId(), request("one"), client)
        supportService.sendUserMessage(user.requireId(), request("two"), client)

        assertEquals(listOf("two"), supportService.getMessages(user.requireId(), first.id).messages.map { it.body })
    }

    @Test
    fun `closed thread reopens on the next user message`() {
        val thread = sendAndGetThreadId()
        supportService.setStatus(thread, SupportThreadStatus.CLOSED)

        supportService.sendUserMessage(user.requireId(), request("again"), client)

        assertEquals(SupportThreadStatus.OPEN, supportService.getThread(thread).thread.status)
    }

    @Test
    fun `admin thread list shows masked label and last message`() {
        sendAndGetThreadId()

        val summary = supportService.listThreads().single { it.userId == user.requireId() }

        assertEquals("Sam Support (su***@gmail.com)", summary.userLabel)
        assertEquals("help", summary.lastMessagePreview)
        assertEquals(SupportSender.USER, summary.lastSender)
    }

    private fun sendAndGetThreadId(): Long {
        supportService.sendUserMessage(user.requireId(), request("help"), client)
        return checkNotNull(
            jdbc.queryForObject("SELECT id FROM support_threads WHERE user_id = ?", Long::class.java, user.requireId())
        )
    }

    private fun request(body: String, clientId: String? = null) = SendSupportMessageRequest(body, clientId)

    private fun reply(messageId: Long, replyTo: Long?, text: String, chatId: Long = ADMIN_CHAT.toLong()) =
        TelegramUpdate(
            updateId = messageId,
            message = TelegramMessage(
                messageId = messageId,
                chat = TelegramChat(chatId),
                from = TelegramUser(isBot = false),
                text = text,
                replyToMessage = replyTo?.let { TelegramMessage(messageId = it) },
            ),
        )

    private fun count(): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM support_messages", Int::class.java))

    private fun clear() {
        jdbc.update("DELETE FROM support_threads")
    }
}

private const val ADMIN_CHAT = "4242"
