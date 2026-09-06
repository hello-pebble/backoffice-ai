package com.backoffice.dashboard.auth

import com.backoffice.dashboard.OfficeProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthControllerTest {
    private val auth = mock(AuthService::class.java)
    private val controller = AuthController(authService = auth, properties = OfficeProperties())

    @Test
    fun `로그인 쿠키는 Max-Age 없는 브라우저 세션 쿠키다`() {
        `when`(auth.completeLogin("코드", "상태")).thenReturn(LoginResult("session-token", null, "owner@example.com"))

        val cookies = controller.authCallback("코드", "상태").headers[HttpHeaders.SET_COOKIE].orEmpty()

        // Max-Age 를 주면 쿠키가 디스크에 남아 브라우저·컴퓨터를 껐다 켜도 로그인이 유지된다.
        assertEquals(2, cookies.size, "실제 쿠키: $cookies")
        assertTrue(cookies.none { it.contains("Max-Age") }, "브라우저를 닫아도 남는 쿠키가 있다: $cookies")
    }

    @Test
    fun `로그아웃하면 세션 쿠키와 표시용 쿠키를 함께 지운다`() {
        val cookies = controller.logout(MockHttpServletRequest()).headers[HttpHeaders.SET_COOKIE].orEmpty()

        // 표시용 쿠키가 남으면 화면이 로그인된 줄 알고 대시보드를 그린 뒤 401 로 튕긴다.
        assertEquals(2, cookies.size, "실제 쿠키: $cookies")
        assertTrue(cookies.any { it.startsWith("${SessionAuthFilter.COOKIE}=;") }, "세션 쿠키 삭제 없음: $cookies")
        assertTrue(cookies.any { it.startsWith("${SessionAuthFilter.HINT_COOKIE}=;") }, "표시용 쿠키 삭제 없음: $cookies")
        assertTrue(cookies.all { it.contains("Max-Age=0") }, "만료 설정 없음: $cookies")
    }

    @Test
    fun `표시용 쿠키는 화면이 읽어야 하므로 HttpOnly 가 아니다`() {
        val cookies = controller.logout(MockHttpServletRequest()).headers[HttpHeaders.SET_COOKIE].orEmpty()

        assertTrue(cookies.first { it.startsWith(SessionAuthFilter.COOKIE + "=") }.contains("HttpOnly"))
        assertEquals(false, cookies.first { it.startsWith(SessionAuthFilter.HINT_COOKIE + "=") }.contains("HttpOnly"))
    }
}
