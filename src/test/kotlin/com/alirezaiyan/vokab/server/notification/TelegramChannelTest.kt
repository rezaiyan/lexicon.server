package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AdminTopic
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.NotificationsConfig
import com.alirezaiyan.vokab.server.shared.TelegramConfig
import com.alirezaiyan.vokab.server.shared.TelegramTopics
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException

class TelegramChannelTest {

    private val sendMessageUrl = "https://api.telegram.org/bot123:ABC/sendMessage"

    @Test
    fun `send without a configured topic posts to the chat's General topic`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """{"chat_id":"87659200","text":"Test Title\nTest body message"}""",
                    JsonCompareMode.STRICT,
                )
            )
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

        channel.send(AdminTopic.USERS, "Test Title", "Test body message")

        server.verify()
    }

    @Test
    fun `send posts into the topic's thread`() {
        val topics = TelegramTopics(users = 11, alerts = 22, digests = 33, support = 44)
        val (channel, server) = channel(botToken = "123:ABC", chatId = "-1001", topics = topics)
        server.expect(requestTo(sendMessageUrl))
            .andExpect(
                content().json(
                    """{"chat_id":"-1001","message_thread_id":22,"text":"Down\nServer"}""",
                    JsonCompareMode.STRICT,
                )
            )
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

        channel.send(AdminTopic.ALERTS, "Down", "Server")

        server.verify()
    }

    @Test
    fun `send is a no-op when bot token is blank`() {
        val (channel, server) = channel(botToken = "", chatId = "87659200")

        channel.send(AdminTopic.ALERTS, "Title", "Body")

        server.verify() // no expectations: any request would have failed
    }

    @Test
    fun `send is a no-op when chat id is blank`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "")

        channel.send(AdminTopic.ALERTS, "Title", "Body")

        server.verify()
    }

    @Test
    fun `send swallows an error status`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        channel.send(AdminTopic.ALERTS, "Title", "Body")
    }

    @Test
    fun `send swallows a network failure`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl)).andRespond(withException(IOException("connection reset")))

        channel.send(AdminTopic.ALERTS, "Title", "Body")
    }

    private fun channel(
        botToken: String,
        chatId: String,
        topics: TelegramTopics = TelegramTopics(),
    ): Pair<TelegramChannel, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return TelegramChannel(createConfig(botToken, chatId, topics), builder) to server
    }

    private fun createConfig(botToken: String, chatId: String, topics: TelegramTopics) = AppProperties(
        notifications = NotificationsConfig(
            admin = NotificationsConfig.AdminConfig(
                enabled = true,
                telegram = TelegramConfig(botToken = botToken, chatId = chatId, topics = topics)
            )
        )
    )
}
