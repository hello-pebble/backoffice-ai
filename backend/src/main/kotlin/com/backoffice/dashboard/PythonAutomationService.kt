package com.backoffice.dashboard

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
) {
    private val workerClient = properties.automation.workerUrl
        .takeIf(String::isNotBlank)
        ?.let { RestClient.builder().baseUrl(it.trimEnd('/')).build() }

    fun run(mode: String): AutomationResponse {
        if (!properties.automation.executionEnabled) {
            return AutomationResponse(false, null, "자동화 실행이 비활성화되어 있습니다.")
        }
        return if (workerClient != null) runRemote(mode) else runLocal(mode)
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

