package com.backoffice.dashboard

import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit

@Service
class PythonAutomationService(private val properties: OfficeProperties) {
    fun run(mode: String): AutomationResponse {
        return try {
            val process = ProcessBuilder(properties.automation.pythonExecutable, "main.py", "--mode", mode)
                .directory(File(properties.automation.workingDirectory))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return AutomationResponse(false, null, "자동화 작업이 5분을 초과해 중지되었습니다.")
            }
            val output = process.inputStream.bufferedReader().readText().takeLast(12_000)
            AutomationResponse(process.exitValue() == 0, process.exitValue(), output)
        } catch (error: Exception) {
            AutomationResponse(false, null, "Python 자동화를 실행하지 못했습니다: ${error.message}")
        }
    }
}
