package com.backoffice.dashboard

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit

@Service
class PythonAutomationService(
    private val properties: OfficeProperties,
    @Value("\${app.automation.timeout-minutes:5}") private val timeoutMinutes: Long,
) {
    fun run(mode: String): AutomationResponse {
        if (!properties.automation.executionEnabled) {
            return AutomationResponse(false, null, "OCI에서는 자동화 워커가 별도 컨테이너로 실행됩니다.")
        }
        return try {
            val process = ProcessBuilder(properties.automation.pythonExecutable, "-m", "automation.main", "--mode", mode)
                .directory(File(properties.automation.workingDirectory))
                .redirectErrorStream(true)
                .start()
            // ponytail: 출력을 메모리에 전부 담고 takeLast(12_000)은 사후에만 적용된다.
            // ponytail: buffers the whole child output in memory; upgrade path is streaming to a log sink if output ever gets large.
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return AutomationResponse(false, null, "자동화 작업이 ${timeoutMinutes}분을 초과해 중지되었습니다.")
            }
            AutomationResponse(process.exitValue() == 0, process.exitValue(), output.takeLast(12_000))
        } catch (error: Exception) {
            AutomationResponse(false, null, "Python 자동화를 실행하지 못했습니다: ${error.message}")
        }
    }
}
