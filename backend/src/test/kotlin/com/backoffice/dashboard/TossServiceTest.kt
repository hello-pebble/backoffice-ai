package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TossServiceTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @AfterEach fun stop() = server.stop(0)

    private fun serve(path: String, status: Int, body: String) {
        server.createContext(path) { exchange ->
            val payload = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
    }

    private fun service(
        enabled: Boolean = true,
        clientId: String = "id",
        clientSecret: String = "secret",
        started: Boolean = true,
    ): TossService {
        if (started) server.start()
        return TossService(
            OfficeProperties(
                toss = OfficeProperties.Toss(
                    enabled = enabled,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    clientId = clientId,
                    clientSecret = clientSecret,
                ),
            ),
            ObjectMapper(),
        )
    }

    @Test
    fun `연동이 꺼져 있으면 호출하지 않고 안내만 한다`() {
        val overview = service(enabled = false, started = false).overview()

        assertFalse(overview.connected)
        assertEquals("토스증권 연동이 비활성화되어 있습니다.", overview.message)
    }

    @Test
    fun `자격증명이 비어 있으면 안내만 한다`() {
        val overview = service(clientSecret = "", started = false).overview()

        assertFalse(overview.connected)
        assertEquals("토스증권 Open API 자격증명이 아직 설정되지 않았습니다.", overview.message)
    }

    @Test
    fun `시세를 받아 종목 이름을 붙인다`() {
        serve("/oauth2/token", 200, """{"access_token":"t-1"}""")
        serve("/api/v1/prices", 200, """{"result":[
            {"symbol":"005930","lastPrice":"71000","currency":"KRW","timestamp":"2026-08-26T09:00:00Z"},
            {"symbol":"999999","lastPrice":"1000"}
        ]}""")

        val overview = service().overview()

        assertTrue(overview.connected)
        assertEquals(listOf("삼성전자", "999999"), overview.items.map { it.name })
        assertEquals("71000", overview.items.first().price)
        // 통화가 없으면 KRW 로 본다. 화면이 원화로 표시하기 때문이다.
        assertEquals("KRW", overview.items.last().currency)
    }

    @Test
    fun `토큰을 못 받으면 실패로 처리하고 예외를 밖으로 던지지 않는다`() {
        serve("/oauth2/token", 200, "{}")

        val overview = service().overview()

        assertFalse(overview.connected)
        assertEquals("토스증권 시세를 불러오지 못했습니다.", overview.message)
    }

    @Test
    fun `시세 조회가 실패해도 대시보드 전체를 막지 않는다`() {
        serve("/oauth2/token", 200, """{"access_token":"t-1"}""")
        serve("/api/v1/prices", 500, "boom")

        val overview = service().overview()

        assertFalse(overview.connected)
        assertEquals("토스증권 시세를 불러오지 못했습니다.", overview.message)
    }
}
