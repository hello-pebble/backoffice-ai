package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerAuthFilterTest {
    private val key = "worker-shared-key"

    private fun filter(configuredKey: String = key) =
        WorkerAuthFilter(OfficeProperties(automation = OfficeProperties.Automation(workerApiKey = configuredKey)))

    private fun request(uri: String = "/api/worker/keywords", header: String? = null) =
        MockHttpServletRequest("POST", uri).apply { header?.let { addHeader("X-Worker-API-Key", it) } }

    @Test
    fun `맞는 키는 통과한다`() {
        val chain = mock(FilterChain::class.java)
        val request = request(header = key)
        val response = MockHttpServletResponse()

        filter().doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
    }

    @Test
    fun `키가 없거나 틀리면 401 이다`() {
        listOf(null, "틀린-키").forEach { header ->
            val chain = mock(FilterChain::class.java)
            val request = request(header = header)
            val response = MockHttpServletResponse()

            filter().doFilter(request, response, chain)

            verify(chain, never()).doFilter(request, response)
            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("워커 인증에 실패했습니다"))
        }
    }

    @Test
    fun `키가 설정되지 않았으면 아무도 통과시키지 않는다`() {
        val chain = mock(FilterChain::class.java)
        // 빈 키를 빈 헤더와 맞춰 통과시키면, 설정을 빠뜨린 배포가 무인증으로 열린다.
        val request = request(header = "")
        val response = MockHttpServletResponse()

        filter(configuredKey = "").doFilter(request, response, chain)

        verify(chain, never()).doFilter(request, response)
        assertEquals(401, response.status)
    }

    @Test
    fun `워커 경로가 아니면 키 없이도 이 필터를 그냥 지나간다`() {
        // 사람이 쓰는 경로는 SessionAuthFilter 가 지킨다. 여기서 또 막으면 화면이 전부 401 이 된다.
        listOf("/api/topic-drafts", "/index.html").forEach { uri ->
            val chain = mock(FilterChain::class.java)
            val request = MockHttpServletRequest("GET", uri)
            val response = MockHttpServletResponse()

            filter().doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }
    }
}
