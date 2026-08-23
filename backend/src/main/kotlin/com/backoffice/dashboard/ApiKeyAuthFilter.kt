package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

// ponytail: 단일 운영자용 공유 키 1개. 사용자별 인증이 필요해지면 spring-security로 교체.
@Component
class ApiKeyAuthFilter(
    @Value("\${app.auth.enabled:true}") private val enabled: Boolean,
    @Value("\${app.auth.api-key:}") private val apiKey: String,
) : OncePerRequestFilter() {
    private val openPaths = setOf("/api/health", "/api/gmail/callback")

    init {
        check(!enabled || apiKey.isNotBlank()) {
            "app.auth.enabled=true 이지만 app.auth.api-key 가 비어 있습니다. APP_AUTH_API_KEY 환경변수를 설정하거나 APP_AUTH_ENABLED=false 로 두세요."
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !enabled ||
            !request.requestURI.startsWith("/api/") ||
            request.method.equals("OPTIONS", ignoreCase = true) ||
            request.requestURI in openPaths

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val provided = request.getHeader("X-API-Key").orEmpty()
        if (!MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), apiKey.toByteArray(Charsets.UTF_8))) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"detail":"인증에 실패했습니다."}""")
            return
        }
        chain.doFilter(request, response)
    }
}
