package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AiOperationsServiceTest {
    private val service = AiOperationsService(ObjectMapper(), FakeDocumentStore())

    @Test
    fun `오늘 실행의 모델별 횟수와 작업 시간을 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt-5.6-luna", tools = emptyList(), durationMs = 1_500)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt-5.6-luna", tools = emptyList(), durationMs = 2_500)
        service.record(agent = "요약", provider = "Ollama 로컬", model = "llama3.2:1b", tools = emptyList(), durationMs = 6_000)
        // 수집 실행은 모델을 쓰지 않으므로 모델 목록에서 빠진다. 시간은 그대로 합산한다.
        service.record(agent = "뉴스 수집", provider = "RSS", model = "모델 사용 안 함", tools = emptyList(), durationMs = 1_000)

        val overview = service.overview()

        assertEquals(11_000, overview.totalDurationMs)
        assertEquals(listOf(ModelUsage("gpt-5.6-luna", 2), ModelUsage("llama3.2:1b", 1)), overview.models)
    }
}
