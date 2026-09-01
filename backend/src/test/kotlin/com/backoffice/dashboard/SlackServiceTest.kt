package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slack API 는 실패도 HTTP 200 에 {"ok":false} 로 준다. 상태 코드만 보면 성공으로 오인하므로
 * 로컬 서버로 실제 응답 모양을 흉내 내 확인한다.
 */
class SlackServiceTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val documents = FakeDocumentStore()
    private val bodies = mutableMapOf<String, String>()

    @AfterEach fun stop() = server.stop(0)

    private fun serve(method: String, body: String) {
        server.createContext("/api/$method") { exchange ->
            bodies[method] = exchange.requestBody.readAllBytes().decodeToString()
            val payload = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
    }

    private fun service(clientId: String = "app-id", clientSecret: String = "app-secret"): SlackService {
        runCatching { server.start() }
        return SlackService(
            OfficeProperties(slack = OfficeProperties.Slack(
                clientId = clientId,
                clientSecret = clientSecret,
                apiBaseUrl = "http://127.0.0.1:${server.address.port}/api",
            )),
            ObjectMapper(),
            documents,
        )
    }

    private fun connected(channelId: String? = "C1") = documents.write(
        "slack-connection",
        SlackConnection("xoxb-token", "우리 워크스페이스", channelId, channelId?.let { "일반" }, "2026-08-26T09:00:00Z"),
    )

    @Test
    fun `자격증명이 없으면 설치 주소를 만들지 않는다`() {
        val service = service(clientId = "", clientSecret = "")

        assertFalse(service.status().configured)
        assertFailsWith<IllegalArgumentException> { service.installUrl() }
    }

    @Test
    fun `설치 주소에는 필요한 스코프와 리디렉션이 들어간다`() {
        val url = service().installUrl()

        assertTrue(url.startsWith("https://slack.com/oauth/v2/authorize"), url)
        assertTrue(url.contains("client_id=app-id"), url)
        // 알림을 보내려면 chat%3Awrite, 채널 목록을 읽으려면 channels%3Aread 가 필요하다.
        assertTrue(url.contains("chat%3Awrite"), url)
        assertTrue(url.contains("channels%3Aread"), url)
        assertTrue(url.contains("state="), url)
    }

    @Test
    fun `state 가 맞지 않는 콜백은 설치로 인정하지 않는다`() {
        serve("oauth.v2.access", """{"ok":true,"access_token":"xoxb-token","team":{"name":"팀"}}""")
        val service = service()

        // 우리가 만든 적 없는 state = 남이 유도한 콜백.
        assertFalse(service.completeInstall("code", "위조된-state"))
        assertFalse(service.status().connected)
    }

    @Test
    fun `설치가 끝나면 봇 토큰을 저장하고 상태에는 노출하지 않는다`() {
        serve("oauth.v2.access", """{"ok":true,"access_token":"xoxb-token","team":{"name":"우리 워크스페이스"}}""")
        val service = service()
        val state = service.installUrl().substringAfter("state=")

        assertTrue(service.completeInstall("code-1", state))

        val status = service.status()
        assertTrue(status.connected)
        assertEquals("우리 워크스페이스", status.teamName)
        // 채널은 아직 안 골랐으니 알림은 보내지 않는다.
        assertNull(status.channelId)
        assertEquals("NOT_CONFIGURED" to null, service.notify("알림"))
    }

    @Test
    fun `Slack 이 ok false 를 주면 설치 실패로 본다`() {
        serve("oauth.v2.access", """{"ok":false,"error":"invalid_code"}""")
        val service = service()
        val state = service.installUrl().substringAfter("state=")

        assertFalse(service.completeInstall("code-1", state))
        assertFalse(service.status().connected)
    }

    @Test
    fun `연결 전에는 알림을 보내지 않고 초안 저장을 막지도 않는다`() {
        assertEquals("NOT_CONFIGURED" to null, service().notify("알림"))
    }

    @Test
    fun `채널이 정해져 있으면 chat_postMessage 로 보낸다`() {
        connected()
        serve("chat.postMessage", """{"ok":true}""")

        assertEquals("SENT" to null, service().notify("새 검토 대본 초안이 준비됨"))
        assertTrue(bodies.getValue("chat.postMessage").contains("\"channel\":\"C1\""))
    }

    @Test
    fun `Slack 이 ok false 를 주면 전송 실패로 남긴다`() {
        connected()
        serve("chat.postMessage", """{"ok":false,"error":"channel_not_found"}""")

        val (status, error) = service().notify("알림")

        assertEquals("FAILED", status)
        assertTrue(error!!.contains("channel_not_found"), "실제 사유: $error")
    }

    @Test
    fun `연결이 끊겨 있으면 채널 목록을 요청하지 않는다`() {
        assertFailsWith<IllegalArgumentException> { service().channels() }
    }

    @Test
    fun `채널을 고르면 이름까지 저장한다`() {
        connected(channelId = null)
        serve("conversations.list", """{"ok":true,"channels":[{"id":"C1","name":"일반"},{"id":"C2","name":"공지"}]}""")
        val service = service()

        val status = service.selectChannel("C2")

        assertEquals("C2", status.channelId)
        assertEquals("공지", status.channelName)
        assertEquals(listOf("일반", "공지"), service.channels().map { it.name })
    }

    @Test
    fun `봇이 못 보는 채널은 고를 수 없다`() {
        connected(channelId = null)
        serve("conversations.list", """{"ok":true,"channels":[{"id":"C1","name":"일반"}]}""")

        assertFailsWith<IllegalArgumentException> { service().selectChannel("C-없음") }
    }
}
