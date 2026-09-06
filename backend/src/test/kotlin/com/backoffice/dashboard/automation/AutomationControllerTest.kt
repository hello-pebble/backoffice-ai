package com.backoffice.dashboard.automation

import com.backoffice.dashboard.operations.OperationsService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomationControllerTest {
    private val slack = mock(SlackService::class.java)

    private val controller = AutomationController(
        automationService = mock(PythonAutomationService::class.java),
        operationsService = mock(OperationsService::class.java),
        slackService = slack,
    )

    private fun status(block: () -> Any?) = assertFailsWith<ResponseStatusException> { block() }.statusCode

    @Test
    fun `허용하지 않은 자동화 모드는 400 이다`() {
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.runAutomation("드롭테이블") })
    }

    @Test
    fun `Slack 자격증명이 없으면 연결 주소 요청은 409 다`() {
        doThrow(IllegalArgumentException("Slack 앱 자격증명이 설정되지 않았습니다.")).`when`(slack).installUrl()

        assertEquals(HttpStatus.CONFLICT, status { controller.connectSlack() })
    }

    @Test
    fun `Slack 채널 조회는 미연결이면 409, Slack 오류면 502 다`() {
        doThrow(IllegalArgumentException("Slack 이 아직 연결되지 않았습니다.")).`when`(slack).channels()
        assertEquals(HttpStatus.CONFLICT, status { controller.slackChannels() })

        doThrow(IllegalStateException("Slack 채널 목록을 가져오지 못했습니다: invalid_auth")).`when`(slack).channels()
        assertEquals(HttpStatus.BAD_GATEWAY, status { controller.slackChannels() })
    }
}
