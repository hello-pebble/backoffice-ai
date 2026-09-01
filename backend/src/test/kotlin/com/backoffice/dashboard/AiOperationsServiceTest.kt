package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AiOperationsServiceTest {
    private val slack = RecordingSlackService()
    private val service = AiOperationsService(
        ObjectMapper(), FakeDocumentStore(), slack,
        OfficeProperties(slack = OfficeProperties.Slack(reviewBaseUrl = "https://office.example.com")),
    )

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

    @Test
    fun `실패는 Slack 으로 알리고 성공은 알리지 않는다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 10)
        assertEquals(emptyList(), slack.sent, "성공까지 알리면 알림이 소음이 된다")

        service.record(
            agent = "주제 대본 초안 에이전트", provider = "integrate.api.nvidia.com", model = "deepseek",
            tools = emptyList(), durationMs = 10, status = "실패", error = "request timed out",
        )

        // 무엇이·어디서·왜 실패했는지와 어디를 볼지가 들어가야 한다.
        val message = slack.sent.single()
        listOf("주제 대본 초안 에이전트", "integrate.api.nvidia.com", "request timed out", "https://office.example.com/#ai-operations")
            .forEach { assertEquals(true, message.contains(it), "빠진 내용: $it / 실제: $message") }
    }

    @Test
    fun `알림이 터져도 원래 실패 사유를 덮지 않는다`() {
        slack.thrown = RuntimeException("slack down")

        // 여기서 예외가 새면 호출자의 catch 가 Slack 오류를 원래 사유로 착각한다.
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 10, status = "실패", error = "boom")

        assertEquals("boom", service.overview().items.single().error)
    }
}
