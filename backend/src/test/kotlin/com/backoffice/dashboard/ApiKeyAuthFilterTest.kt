package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiKeyAuthFilterTest {
    private val key = "s3cret-key"
    private fun filter(enabled: Boolean = true, apiKey: String = key) = ApiKeyAuthFilter(enabled, apiKey)

    private fun request(uri: String = "/api/tasks", method: String = "GET", header: String? = null) =
        MockHttpServletRequest(method, uri).apply { header?.let { addHeader("X-API-Key", it) } }

    @Test
    fun `올바른 키는 통과한다`() {
        val chain = mock(FilterChain::class.java)
        val request = request(header = key)
        val response = MockHttpServletResponse()

        filter().doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
    }

    @Test
    fun `틀린 키는 401 이고 체인을 호출하지 않는다`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()
        val request = request(header = "wrong")

        filter().doFilter(request, response, chain)

        verify(chain, never()).doFilter(request, response)
        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("인증에 실패했습니다"))
    }

    @Test
    fun `키가 없으면 401 이다`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()
        val request = request()

        filter().doFilter(request, response, chain)

        verify(chain, never()).doFilter(request, response)
        assertEquals(401, response.status)
    }

    @Test
    fun `공개 경로와 OPTIONS 와 비 api 경로는 키 없이 통과한다`() {
        listOf(
            request("/api/health"),
            request("/api/gmail/callback"),
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

        filter(enabled = false, apiKey = "").doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `인증이 켜져 있는데 키가 비어 있으면 생성에 실패한다`() {
        assertFailsWith<IllegalStateException> { ApiKeyAuthFilter(true, "  ") }
    }
}
