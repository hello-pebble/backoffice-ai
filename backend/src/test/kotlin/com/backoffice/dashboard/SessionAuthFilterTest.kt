package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
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
    private val demoToken = AuthService.randomToken()

    @AfterEach
    fun clearDemoContext() = DemoContext.clear()

    private fun filter(enabled: Boolean = true): SessionAuthFilter {
        val properties = OfficeProperties(auth = OfficeProperties.Auth(enabled = enabled, allowedEmails = listOf("owner@example.com")))
        documents.write(
            "auth-sessions",
            listOf(
                AuthSession(AuthService.hash(token), "owner@example.com", OffsetDateTime.now().plusHours(1).toString()),
                AuthSession(AuthService.hash(demoToken), DemoContext.EMAIL, OffsetDateTime.now().plusHours(1).toString()),
            ),
        )
        return SessionAuthFilter(properties, AuthService(properties, documents, PostgresDataStoreFactory(documents), ObjectMapper()))
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
    fun `데모 세션은 허용 목록에 없는 경로에서 403 이고 체인을 호출하지 않는다`() {
        listOf(
            request("/api/automation/content", method = "POST", session = demoToken),
            request("/api/tasks", method = "POST", session = demoToken),
            request("/api/slack/channels", session = demoToken),
            request("/api/topic-drafts/abc/notify", method = "POST", session = demoToken),
            request("/api/instagram-toons", method = "POST", session = demoToken),
            // 읽음 표시와 모양이 비슷하지만 허용 목록에 없다.
            request("/api/ai-news/abc/delete", method = "PATCH", session = demoToken),
        ).forEach { request ->
            val chain = mock(FilterChain::class.java)
            val response = MockHttpServletResponse()

            filter().doFilter(request, response, chain)

            verify(chain, never()).doFilter(request, response)
            assertEquals(403, response.status, request.requestURI)
            assertTrue(response.contentAsString.contains("데모에서는"))
        }
    }

    @Test
    fun `데모 세션은 허용 목록 경로를 통과하고 그 안에서만 데모로 표시된다`() {
        listOf(
            request("/api/topic-drafts/refresh", method = "POST", session = demoToken),
            request("/api/ai-news/refresh", method = "POST", session = demoToken),
            request("/api/dashboard", session = demoToken),
            // id 가 가변이라 접두·접미로 판정한다.
            request("/api/ai-news/abc/read", method = "PATCH", session = demoToken),
        ).forEach { request ->
            val response = MockHttpServletResponse()
            var demoInsideChain = false
            val chain = FilterChain { _, _ -> demoInsideChain = DemoContext.isDemo() }

            filter().doFilter(request, response, chain)

            assertEquals(200, response.status, request.requestURI)
            assertTrue(demoInsideChain, "체인 안에서는 데모로 표시돼야 한다: ${request.requestURI}")
            // 스레드는 재사용된다. 빠져나온 뒤에도 켜져 있으면 다음 요청이 데모 취급된다.
            assertTrue(!DemoContext.isDemo(), "빠져나온 뒤에는 꺼져야 한다")
        }
    }

    @Test
    fun `주인 세션은 데모 표시 없이 그대로 통과한다`() {
        val response = MockHttpServletResponse()
        var demoInsideChain = true
        val chain = FilterChain { _, _ -> demoInsideChain = DemoContext.isDemo() }

        filter().doFilter(request("/api/automation/content", method = "POST", session = token), response, chain)

        assertEquals(200, response.status)
        assertTrue(!demoInsideChain, "주인 요청이 데모로 표시되면 실데이터가 격리돼 안 보인다")
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
