package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 재시도·단가는 돈이 걸린 경로라 실제 HTTP 왕복으로 확인한다. JDK 내장 서버면 충분하다. */
class LlmClientChatTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val calls = AtomicInteger()

    @AfterEach fun stop() = server.stop(0)

    private fun serve(status: () -> Int, body: () -> String) {
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val payload = body().toByteArray()
            exchange.sendResponseHeaders(status(), payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    private fun properties(prices: Map<String, String> = emptyMap()) = OfficeProperties(
        aiNews = OfficeProperties.AiNews(summaryProvider = "openai", openAiApiKey = "test-key", openAiBaseUrl = "http://127.0.0.1:${server.address.port}/v1", summaryModel = "test-model"),
        llm = OfficeProperties.Llm(maxAttempts = 3, retryDelayMillis = 1, prices = prices),
    )

    private fun okBody(inputTokens: Int, outputTokens: Int) =
        """{"choices":[{"message":{"content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":$inputTokens,"completion_tokens":$outputTokens}}"""

    @Test
    fun `5xx 는 재시도하고 성공하면 그 응답을 쓴다`() {
        serve(status = { if (calls.get() < 3) 503 else 200 }, body = { okBody(10, 20) })

        val response = LlmClient(properties(), ObjectMapper()).chat("s", "u")

        assertEquals(3, calls.get(), "두 번 실패한 뒤 세 번째 호출이 성공해야 한다")
        assertTrue(response.content.contains("ok"))
        assertEquals(10, response.inputTokens)
        assertEquals(20, response.outputTokens)
    }

    @Test
    fun `4xx 는 다시 보내도 같은 답이라 한 번만 호출한다`() {
        serve(status = { 400 }, body = { "모델 이름이 잘못되었습니다" })

        val error = assertFailsWith<IllegalStateException> { LlmClient(properties(), ObjectMapper()).chat("s", "u") }

        assertEquals(1, calls.get())
        assertTrue(error.message!!.contains("400"), "상태 코드가 사유에 남아야 한다: ${error.message}")
    }

    @Test
    fun `모델별 단가표가 있으면 그 값으로 비용을 계산한다`() {
        serve(status = { 200 }, body = { okBody(1_000_000, 1_000_000) })

        val withTable = LlmClient(properties(mapOf("test-model" to "2.0, 8.0")), ObjectMapper()).chat("s", "u")
        assertEquals(10.0, withTable.costUsd, 0.0001)

        // 표에 없는 모델은 기존 기본 단가(0.20 / 1.20)로 떨어진다.
        val fallback = LlmClient(properties(mapOf("다른-모델" to "2.0,8.0")), ObjectMapper()).chat("s", "u")
        assertEquals(1.4, fallback.costUsd, 0.0001)
    }
}
