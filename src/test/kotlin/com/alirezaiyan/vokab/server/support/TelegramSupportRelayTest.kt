package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.NotificationsConfig
import com.alirezaiyan.vokab.server.shared.TelegramConfig
import com.alirezaiyan.vokab.server.shared.TelegramTopics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class TelegramSupportRelayTest {

    @Test
    fun `post goes to the Support topic and returns the message id`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val relay = TelegramSupportRelay(config(TelegramTopics(support = 44, alerts = 22)), builder)
        server.expect(requestTo("https://api.telegram.org/bot123:ABC/sendMessage"))
            .andExpect(
                content().json(
                    """{"chat_id":"-1001","message_thread_id":44,"text":"Help please"}""",
                    JsonCompareMode.STRICT,
                )
            )
            .andRespond(withSuccess("""{"ok":true,"result":{"message_id":9}}""", MediaType.APPLICATION_JSON))

        assertEquals(9L, relay.post("Help please"))
        server.verify()
    }

    private fun config(topics: TelegramTopics) = AppProperties(
        notifications = NotificationsConfig(
            admin = NotificationsConfig.AdminConfig(
                telegram = TelegramConfig(botToken = "123:ABC", chatId = "-1001", topics = topics)
            )
        )
    )
}
