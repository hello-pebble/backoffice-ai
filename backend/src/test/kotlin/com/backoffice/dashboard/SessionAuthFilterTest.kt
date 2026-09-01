package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAuthFilterTest {
    private val documents = FakeDocumentStore()
    private val token = AuthService.randomToken()

    private fun filter(enabled: Boolean = true): SessionAuthFilter {
        val properties = OfficeProperties(auth = OfficeProperties.Auth(enabled = enabled, allowedEmails = listOf("owner@example.com")))
        documents.write("auth-sessions", listOf(AuthSession(AuthService.hash(token), "owner@example.com", OffsetDateTime.now().plusHours(1).toString())))
        return SessionAuthFilter(properties, AuthService(properties, documents))
    }

    private fun request(uri: String = "/api/tasks", method: String = "GET", session: String? = null) =
        MockHttpServletRequest(method, uri).apply { session?.let { setCookies(Cookie(SessionAuthFilter.COOKIE, it)) } }

    @Test
    fun `살아 있는 세션 쿠키는 통과한다`() {
        val chain = mock(FilterChain::class.java)
        val request = request(session = token)
        val response = MockHttpServletResponse()

        filter().doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
    }

    @Test
    fun `쿠키가 없거나 모르는 값이면 401 이고 체인을 호출하지 않는다`() {
        listOf(null, "모르는-토큰").forEach { session ->
            val chain = mock(FilterChain::class.java)
            val request = request(session = session)
            val response = MockHttpServletResponse()

            filter().doFilter(request, response, chain)

            verify(chain, never()).doFilter(request, response)
            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("로그인이 필요합니다"))
        }
    }

    @Test
    fun `공개 경로와 OPTIONS 와 비 api 경로는 세션 없이 통과한다`() {
        listOf(
            request("/api/health"),
            request("/api/auth/login"),
            request("/api/auth/callback"),
            request("/api/gmail/callback"),
            // 설치 후 Slack 이 브라우저를 되돌려 보낸다. 쿠키를 붙여 줄 수 없다.
            request("/api/slack/callback"),
            request("/api/tasks", method = "OPTIONS"),
            request("/index.html"),
        ).forEach { request ->
            val chain = mock(FilterChain::class.java)
            val response = MockHttpServletResponse()

            filter().doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
            assertEquals(200, response.status, request.requestURI + " " + request.method)
        }
    }

    @Test
    fun `인증이 꺼져 있으면 모든 요청이 통과한다`() {
        val chain = mock(FilterChain::class.java)
        val request = request()
        val response = MockHttpServletResponse()

        filter(enabled = false).doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }
}
