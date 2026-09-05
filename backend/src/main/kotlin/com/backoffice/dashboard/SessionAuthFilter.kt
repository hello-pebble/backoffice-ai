package com.backoffice.dashboard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 로그인 세션 쿠키로 /api 경로를 지킨다. 공유 API 키는 없앴고, 사람이든 스크립트든 로그인해야 한다.
 *
 * 공개 경로는 네 곳뿐이다. 헬스체크(배포 플랫폼이 부른다)와 로그인 시작·콜백,
 * 그리고 외부 서비스가 브라우저를 되돌려 보내는 OAuth 콜백. 콜백은 헤더나 쿠키를 붙일 수 없다.
 */
@Component
class SessionAuthFilter(
    private val properties: OfficeProperties,
    private val authService: AuthService,
) : OncePerRequestFilter() {
    private val openPaths = setOf(
        "/api/health",
        "/api/auth/login",
        "/api/auth/callback",
        "/api/slack/callback",
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.auth.enabled ||
            !request.requestURI.startsWith("/api/") ||
            // 워커 경로는 사람이 아니라 워커가 부른다. WorkerAuthFilter 가 전용 키로 지킨다.
            request.requestURI.startsWith(WorkerAuthFilter.PREFIX) ||
            request.method.equals("OPTIONS", ignoreCase = true) ||
            request.requestURI in openPaths

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (authService.emailOf(sessionToken(request)) == null) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"detail":"로그인이 필요합니다."}""")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val COOKIE = "office_session"

        /** 화면이 로그인 여부를 즉시 알기 위한 표시용 쿠키. 인증 판단에는 쓰지 않는다. */
        const val HINT_COOKIE = "office_session_hint"

        fun sessionToken(request: HttpServletRequest): String? =
            request.cookies?.firstOrNull { it.name == COOKIE }?.value
    }
}
