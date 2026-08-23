package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonAutomationServiceTest {
    @Test
    fun `실행이 비활성화되면 프로세스를 띄우지 않고 안내 문구를 반환한다`() {
        val properties = OfficeProperties(automation = OfficeProperties.Automation(executionEnabled = false))

        val response = PythonAutomationService(properties, 5).run("keywords")

        assertFalse(response.success)
        assertNull(response.exitCode)
        assertTrue(response.output.contains("자동화 워커"))
    }

    @Test
    fun `실행 파일이 없으면 예외 대신 실패 응답을 돌려준다`() {
        val properties = OfficeProperties(
            automation = OfficeProperties.Automation(pythonExecutable = "이런-실행파일은-없다", workingDirectory = "."),
        )

        val response = PythonAutomationService(properties, 1).run("keywords")

        assertFalse(response.success)
        assertNull(response.exitCode)
        assertTrue(response.output.startsWith("Python 자동화를 실행하지 못했습니다"))
    }
}
