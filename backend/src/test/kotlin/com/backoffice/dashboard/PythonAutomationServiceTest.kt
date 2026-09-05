package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonAutomationServiceTest {
    private fun service(properties: OfficeProperties, timeoutMinutes: Long) = PythonAutomationService(
        properties, timeoutMinutes, ObjectMapper(), mock(AiOperationsService::class.java), mock(LlmClient::class.java),
    )

    @Test
    fun `실행이 비활성화되면 프로세스를 띄우지 않고 안내 문구를 반환한다`() {
        val properties = OfficeProperties(automation = OfficeProperties.Automation(executionEnabled = false))

        val response = service(properties, 5).run("keyword")

        assertFalse(response.success)
        assertNull(response.exitCode)
        // "자동화 워커"는 runRemote 의 호출 실패 문구다. 실행 자체가 꺼진 경로에는 나오지 않는다.
        assertTrue(response.output.contains("비활성화"))
    }

    @Test
    fun `실행 파일이 없으면 예외 대신 실패 응답을 돌려준다`() {
        val properties = OfficeProperties(
            automation = OfficeProperties.Automation(pythonExecutable = "이런-실행파일은-없다", workingDirectory = "."),
        )

        val response = service(properties, 1).run("keyword")

        assertFalse(response.success)
        assertNull(response.exitCode)
        assertTrue(response.output.startsWith("Python 자동화를 실행하지 못했습니다"))
    }

    @Test
    fun `워커가 돌려준 AI_USAGE 줄을 운영 센터에 기록하고 출력에서 지운다`() {
        val properties = OfficeProperties()
        val operations = AiOperationsService(ObjectMapper(), FakeDocumentStore(), RecordingSlackService(), properties)
        val service = PythonAutomationService(properties, 5, ObjectMapper(), operations, LlmClient(properties, ObjectMapper()))
        val output = listOf(
            "콘텐츠 생성 완료: 2개",
            """AI_USAGE {"model":"gpt-3.5-turbo","input_tokens":1200,"output_tokens":3400,"calls":6}""",
        ).joinToString(System.lineSeparator())

        val response = service.recordUsage("content", AutomationResponse(true, 0, output), 4_000)

        // 사용량 줄은 사람이 볼 출력이 아니다.
        assertEquals("콘텐츠 생성 완료: 2개", response.output)
        val run = operations.overview().items.single()
        assertEquals("gpt-3.5-turbo", run.model)
        assertEquals(1_200, run.inputTokens)
        assertEquals(3_400, run.outputTokens)
        // 기본 단가 0.20 / 1.20 기준
        assertEquals(1_200 * 0.20 / 1_000_000 + 3_400 * 1.20 / 1_000_000, run.estimatedCostUsd, 1e-9)
    }

    @Test
    fun `AI_USAGE 줄이 없으면 아무것도 기록하지 않는다`() {
        val properties = OfficeProperties()
        val operations = AiOperationsService(ObjectMapper(), FakeDocumentStore(), RecordingSlackService(), properties)
        val service = PythonAutomationService(properties, 5, ObjectMapper(), operations, LlmClient(properties, ObjectMapper()))

        val response = service.recordUsage("keyword", AutomationResponse(true, 0, "키워드 수집 완료: 5개"), 1_000)

        assertEquals("키워드 수집 완료: 5개", response.output)
        assertTrue(operations.overview().items.isEmpty())
    }
}
