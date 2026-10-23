package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.NotificationsConfig
import com.alirezaiyan.vokab.server.shared.TelegramConfig
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
    fun `send posts the chat id and text to the Bot API`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"chat_id":"87659200","text":"Test Title\nTest body message"}"""))
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

        channel.send("Test Title", "Test body message")

        server.verify()
    }

    @Test
    fun `send is a no-op when bot token is blank`() {
        val (channel, server) = channel(botToken = "", chatId = "87659200")

        channel.send("Title", "Body")

        server.verify() // no expectations: any request would have failed
    }

    @Test
    fun `send is a no-op when chat id is blank`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "")

        channel.send("Title", "Body")

        server.verify()
    }

    @Test
    fun `send swallows an error status`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl)).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        channel.send("Title", "Body")
    }

    @Test
    fun `send swallows a network failure`() {
        val (channel, server) = channel(botToken = "123:ABC", chatId = "87659200")
        server.expect(requestTo(sendMessageUrl)).andRespond(withException(IOException("connection reset")))

        channel.send("Title", "Body")
    }

    private fun channel(botToken: String, chatId: String): Pair<TelegramChannel, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return TelegramChannel(createConfig(botToken, chatId), builder) to server
    }

    private fun createConfig(botToken: String, chatId: String) = AppProperties(
        notifications = NotificationsConfig(
            admin = NotificationsConfig.AdminConfig(
                enabled = true,
                telegram = TelegramConfig(botToken = botToken, chatId = chatId)
            )
        )
    )
}
