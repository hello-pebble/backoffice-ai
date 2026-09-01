package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 모델 응답을 흉내 내는 로컬 서버로 요약 저장·검증·운영 기록을 확인한다. */
class AiNewsBriefingServiceTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val documents = FakeDocumentStore()
    private val operations = AiOperationsService(ObjectMapper(), documents)
    private val news = mock(AiNewsService::class.java)

    @AfterEach fun stop() = server.stop(0)

    private fun serve(status: Int, body: String) {
        server.createContext("/") { exchange ->
            val payload = body.toByteArray()
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    private fun service(): AiNewsBriefingService {
        val properties = OfficeProperties(
            aiNews = OfficeProperties.AiNews(
                summaryProvider = "openai",
                openAiApiKey = "test-key",
                openAiBaseUrl = "http://127.0.0.1:${server.address.port}/v1",
                summaryModel = "test-model",
            ),
            llm = OfficeProperties.Llm(maxAttempts = 1, retryDelayMillis = 1),
        )
        return AiNewsBriefingService(news, ObjectMapper(), operations, documents, LlmClient(properties, ObjectMapper()))
    }

    private fun item(id: String, category: String) = AiNewsItem(
        id = id, source = "OpenAI", title = "제목 $id", url = "https://example.com/$id", summary = "요약 $id",
        publishedAt = OffsetDateTime.parse("2026-08-25T09:00:00Z").toString(), category = category,
        read = false, collectedAt = OffsetDateTime.parse("2026-08-25T10:00:00Z").toString(),
    )

    private fun modelReply(itemsJson: String) =
        """{"choices":[{"message":{"content":${ObjectMapper().writeValueAsString("""{"items":$itemsJson}""")}}}],"usage":{"prompt_tokens":300,"completion_tokens":120}}"""

    @Test
    fun `요약 3건을 저장하고 토큰과 비용을 남긴다`() {
        `when`(news.list()).thenReturn(listOf(item("a", "모델"), item("b", "에이전트"), item("c", "업계 소식")))
        serve(200, modelReply("""[{"id":"a","summary":"요약 A","impact":"영향 A"},{"id":"b","summary":"요약 B","impact":"영향 B"},{"id":"c","summary":"요약 C","impact":"영향 C"}]"""))

        val briefing = service().refresh()

        assertEquals(3, briefing.items.size)
        assertEquals("test-model", briefing.model)
        // 중요도 순으로 3건을 고른다: 모델 5 > 에이전트 4 > 기타 1
        assertEquals(listOf("a", "b", "c"), briefing.news.map { it.id })
        assertEquals(briefing, service().get(), "저장까지 끝나야 화면 새로고침에서 보인다")

        val run = operations.overview().items.single()
        assertEquals("성공", run.status)
        assertEquals(300, run.inputTokens)
        assertEquals(120, run.outputTokens)
        assertEquals(300 * 0.20 / 1_000_000 + 120 * 1.20 / 1_000_000, run.estimatedCostUsd, 1e-9)
    }

    @Test
    fun `소식이 3건 미만이면 모델을 부르지 않는다`() {
        `when`(news.list()).thenReturn(listOf(item("a", "모델")))
        serve(200, modelReply("[]"))

        val error = assertFailsWith<IllegalArgumentException> { service().refresh() }

        assertEquals("AI 소식을 먼저 수집한 뒤 요약하세요.", error.message)
    }

    @Test
    fun `모델이 3건을 안 주면 저장하지 않는다`() {
        `when`(news.list()).thenReturn(listOf(item("a", "모델"), item("b", "에이전트"), item("c", "업계 소식")))
        serve(200, modelReply("""[{"id":"a","summary":"요약 A","impact":"영향 A"}]"""))

        assertFailsWith<IllegalArgumentException> { service().refresh() }

        assertEquals(null, service().get(), "형식이 어긋난 요약은 저장하면 안 된다")
        assertEquals("실패", operations.overview().items.single().status)
    }

    @Test
    fun `모델 호출이 실패하면 대상 주소와 사유를 운영 센터에 남긴다`() {
        `when`(news.list()).thenReturn(listOf(item("a", "모델"), item("b", "에이전트"), item("c", "업계 소식")))
        serve(401, "unauthorized")

        val error = assertFailsWith<IllegalStateException> { service().refresh() }

        assertTrue(error.message!!.contains("chat/completions"), "실제 메시지: ${error.message}")
        val run = operations.overview().items.single()
        assertEquals("실패", run.status)
        assertTrue(run.error!!.contains("401"), "실제 기록: ${run.error}")
        assertTrue(run.error!!.contains("127.0.0.1"), "어느 주소로 보냈는지 남아야 원인을 좁힐 수 있다: ${run.error}")
    }
}
