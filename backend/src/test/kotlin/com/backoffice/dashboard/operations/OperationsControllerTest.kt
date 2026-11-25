package com.backoffice.dashboard.operations

import com.backoffice.dashboard.AiOperationsService
import com.backoffice.dashboard.DemoContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationsControllerTest {
    private val gmail = mock(GmailService::class.java)
    private val toss = mock(TossService::class.java)
    private val jdbc = mock(JdbcTemplate::class.java)

    private val controller = OperationsController(
        gmailService = gmail,
        tossService = toss,
        operationsService = mock(OperationsService::class.java),
        aiOperationsService = mock(AiOperationsService::class.java),
        automationRepository = mock(AutomationRepository::class.java),
        jdbc = jdbc,
    )

    @AfterEach
    fun clearDemoContext() = DemoContext.clear()

    @Test
    fun `데모 대시보드는 주인 자격증명으로 구글·토스를 부르지 않는다`() {
        DemoContext.set("세션-해시")

        val response = controller.dashboard()

        verify(gmail, never()).overview()
        verify(toss, never()).overview()
        // 발신자가 실제 주소면 주인 메일함이 샌 것이다.
        assertTrue(response.gmail.messages.all { it.from.endsWith("@example.com") }, "데모 메일은 예시 주소여야 한다")
        assertTrue(response.generatedAt.isNotBlank())
    }

    @Test
    fun `DB 가 죽으면 health 는 503 이다`() {
        `when`(jdbc.queryForObject("select 1", Int::class.java)).thenThrow(RuntimeException("connection refused"))

        val response = controller.health()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals(false, response.body?.get("ok"))
        assertEquals("down", response.body?.get("database"))
    }
}
