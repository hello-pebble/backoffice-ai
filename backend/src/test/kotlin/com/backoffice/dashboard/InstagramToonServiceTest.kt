package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 워커를 띄우기 전에 걸러야 하는 조건만 검증한다.
 * 이 검사가 빠지면 잘못된 입력으로 파이썬 프로세스를 2분까지 붙잡고 있게 된다.
 */
class InstagramToonServiceTest {
    private fun service(executionEnabled: Boolean = true) = InstagramToonService(
        OfficeProperties(automation = OfficeProperties.Automation(executionEnabled = executionEnabled)),
        ObjectMapper(),
        mock(AiOperationsService::class.java),
        mock(LlmClient::class.java),
    )

    private val request = CreateInstagramToonRequest(episode = "출근길에 우산을 두고 온 날 이야기", panelCount = 4)

    @Test
    fun `자동화 실행이 꺼져 있으면 워커를 부르지 않는다`() {
        val error = assertFailsWith<IllegalStateException> { service(executionEnabled = false).generate(request) }

        assertEquals("인스타툰 생성은 OCI 자동화 워커 연결 후 사용할 수 있습니다.", error.message)
    }

    @Test
    fun `에피소드가 10자 미만이면 거부한다`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service().generate(request.copy(episode = "짧아요"))
        }

        assertEquals("에피소드는 10자 이상 입력하세요.", error.message)
    }

    @Test
    fun `컷 수는 4 또는 8 만 받는다`() {
        val error = assertFailsWith<IllegalArgumentException> { service().generate(request.copy(panelCount = 6)) }

        assertEquals("컷 수는 4 또는 8만 가능합니다.", error.message)
    }

    @Test
    fun `대본 디렉터리가 없으면 빈 목록이다`() {
        // 첫 실행 직후 상태. 여기서 예외가 나면 대시보드 첫 화면 전체가 비어 버린다.
        assertEquals(emptyList(), service().list())
    }
}
