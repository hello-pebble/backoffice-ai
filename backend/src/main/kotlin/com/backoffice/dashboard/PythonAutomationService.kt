package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.File
import java.util.concurrent.TimeUnit

@Service
class PythonAutomationService(
    private val properties: OfficeProperties,
    @Value("\${app.automation.timeout-minutes:5}") private val timeoutMinutes: Long,
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
    private val llm: LlmClient,
) {
    private val workerClient = properties.automation.workerUrl
        .takeIf(String::isNotBlank)
        ?.let { RestClient.builder().baseUrl(it.trimEnd('/')).build() }

    fun run(mode: String): AutomationResponse {
        if (!properties.automation.executionEnabled) {
            return AutomationResponse(false, null, "자동화 실행이 비활성화되어 있습니다.")
        }
        val startedAt = System.nanoTime()
        val response = if (workerClient != null) runRemote(mode) else runLocal(mode)
        // 워커는 stdout 한 줄로만 사용량을 돌려준다. 로컬 실행과 원격 워커 모두 같은 경로다.
        return recordUsage(mode, response, (System.nanoTime() - startedAt) / 1_000_000)
    }

    /** 출력에서 AI_USAGE 줄을 찾아 AI 운영 센터에 기록하고, 그 줄은 화면 출력에서 뺀다. */
    internal fun recordUsage(mode: String, response: AutomationResponse, durationMs: Long): AutomationResponse {
        val marked = response.output.lines().lastOrNull { it.trimStart().startsWith(USAGE_MARKER) } ?: return response
        val usage = runCatching { objectMapper.readTree(marked.trimStart().removePrefix(USAGE_MARKER)) }.getOrNull()
        val model = usage?.path("model")?.asText("").orEmpty()
        val calls = usage?.path("calls")?.asInt(0) ?: 0
        if (model.isNotBlank() && calls > 0) {
            val inputTokens = usage!!.path("input_tokens").asLong(0)
            val outputTokens = usage.path("output_tokens").asLong(0)
            aiOperationsService.record(
                agent = "블로그 자동화 워커 · $mode",
                provider = LlmClient.vendorOf(false, System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1"),
                model = model,
                tools = listOf("Python 자동화 워커", "OpenAI 호환 모델"),
                durationMs = durationMs,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = llm.estimateCostUsd(model, inputTokens, outputTokens),
                status = if (response.success) "성공" else "실패",
                resultPreview = "LLM 호출 ${calls}회로 콘텐츠를 생성했습니다.",
            )
        }
        return response.copy(output = response.output.lines().filterNot { it.trimStart().startsWith(USAGE_MARKER) }.joinToString(System.lineSeparator()).trim())
    }

    companion object {
        // automation/shared/usage.py 의 MARKER 와 같은 값이어야 한다.
        const val USAGE_MARKER = "AI_USAGE "
    }

    private fun runRemote(mode: String): AutomationResponse = try {
        workerClient!!.post()
            .uri("/run")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Worker-API-Key", properties.automation.workerApiKey)
            .body(mapOf("mode" to mode))
            .retrieve()
            .body(AutomationResponse::class.java)
            ?: AutomationResponse(false, null, "워커가 빈 응답을 반환했습니다.")
    } catch (error: Exception) {
        AutomationResponse(false, null, "자동화 워커를 호출하지 못했습니다: ${error.message}")
    }

    private fun runLocal(mode: String): AutomationResponse {
        return try {
            val process = ProcessBuilder(properties.automation.pythonExecutable, "-m", "automation.main", "--mode", mode)
                .directory(File(properties.automation.workingDirectory))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                AutomationResponse(false, null, "자동화 작업이 ${timeoutMinutes}분을 초과해 중지되었습니다.")
            } else {
                AutomationResponse(process.exitValue() == 0, process.exitValue(), output.takeLast(12_000))
            }
        } catch (error: Exception) {
            AutomationResponse(false, null, "Python 자동화를 실행하지 못했습니다: ${error.message}")
        }
    }
}

