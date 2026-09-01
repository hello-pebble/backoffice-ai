package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals

/** 컨트롤러가 쓴 한글 사유가 detail 로 내려가야 화면이 그대로 보여 준다. */
class ApiErrorHandlerTest {
    private val handler = ApiErrorHandler()

    @Test
    fun `사유를 detail 로 내리고 상태 코드를 유지한다`() {
        val response = handler.handle(ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 요약에 실패했습니다."))

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("AI 요약에 실패했습니다.", response.body?.get("detail"))
    }

    @Test
    fun `사유가 없으면 기본 문구를 넣는다`() {
        val response = handler.handle(ResponseStatusException(HttpStatus.BAD_REQUEST))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("요청을 처리하지 못했습니다.", response.body?.get("detail"))
    }
}
