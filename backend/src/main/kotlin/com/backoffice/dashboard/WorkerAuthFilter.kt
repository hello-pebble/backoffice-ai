package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * 워커 전용 경로만 공유 키로 연다. 워커는 브라우저가 아니라 Google 로그인을 할 수 없다.
 *
 * 키는 백엔드가 워커를 호출할 때 쓰는 것과 같은 값(office.automation.worker-api-key)이다.
 * 두 서비스가 이미 서로 들고 있는 비밀을 양방향으로 쓴다. 키가 비어 있으면 전부 거부한다 —
 * 설정을 빠뜨렸을 때 무인증으로 열리는 편보다 막히는 편이 안전하다.
 */
@Component
class WorkerAuthFilter(private val properties: OfficeProperties) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(PREFIX) || request.method.equals("OPTIONS", ignoreCase = true)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val expected = properties.automation.workerApiKey
        val provided = request.getHeader("X-Worker-API-Key").orEmpty()
        // 타이밍 공격 방지용 상수 시간 비교. 키가 비면 어떤 값과도 일치하지 않도록 먼저 막는다.
        if (expected.isBlank() || !MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"detail":"워커 인증에 실패했습니다."}""")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val PREFIX = "/api/worker/"
    }
}
