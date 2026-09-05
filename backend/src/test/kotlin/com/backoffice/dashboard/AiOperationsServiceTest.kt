package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiOperationsServiceTest {
    private val slack = RecordingSlackService()
    private val store = FakeDocumentStore()
    private val service = AiOperationsService(
        store, slack,
        OfficeProperties(slack = OfficeProperties.Slack(reviewBaseUrl = "https://office.example.com")),
    )

    private fun run(id: String, executedAt: String) = AiOperationRun(
        id = id, executedAt = executedAt, agent = "브리핑", provider = "openai", model = "gpt",
        status = "성공", durationMs = 1, inputTokens = 0, outputTokens = 0, estimatedCostUsd = 0.0,
        tools = emptyList(), resultPreview = "",
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
    fun `표기만 다른 같은 모델은 한 이름으로 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "OpenAI/GPT-4o ", tools = emptyList(), durationMs = 1)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt-4o", tools = emptyList(), durationMs = 1)
        // 정규화 전에 저장된 기록도 읽을 때 같은 이름으로 합쳐진다.
        store.write("ai-operations", service.overview().items + run("legacy", java.time.OffsetDateTime.now().toString()).copy(model = "Gpt-4o"))

        assertEquals(listOf(ModelUsage("gpt-4o", 3)), service.overview().models)
    }

    @Test
    fun `오늘 입력·출력 토큰을 따로 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1, inputTokens = 400, outputTokens = 100)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1, inputTokens = 600, outputTokens = 250)

        val overview = service.overview()

        assertEquals(1_000, overview.inputTokens)
        assertEquals(350, overview.outputTokens)
        assertEquals(1_350, overview.totalTokens)
    }

    @Test
    fun `여섯 달째 달 1일보다 오래된 실행은 읽을 때 걸러진다`() {
        val firstKeptDay = LocalDate.now().withDayOfMonth(1).minusMonths(5)
        store.write(
            "ai-operations",
            listOf(
                run("too-old", firstKeptDay.minusDays(1).atStartOfDay().toString() + "+09:00"),
                run("boundary", firstKeptDay.atStartOfDay().toString() + "+09:00"),
                // 형식이 깨진 시각은 버리지 않는다. 기록을 잃는 것보다 한 줄 더 보이는 게 낫다.
                run("unparsable", "언제인지 모름"),
            ),
        )

        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1)

        val ids = service.overview().items.map { it.id }
        assertEquals(3, ids.size)
        assertTrue("too-old" !in ids, "여섯 달째 달 1일 이전은 사라져야 한다")
        assertTrue("boundary" in ids && "unparsable" in ids)
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
