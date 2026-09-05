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
        // 데모 시작은 로그인 전에 눌러야 하므로 열어 둔다. office.demo.enabled 가 꺼져 있으면 서비스가 막는다.
        "/api/auth/demo",
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.auth.enabled ||
            !request.requestURI.startsWith("/api/") ||
            // 워커 경로는 사람이 아니라 워커가 부른다. WorkerAuthFilter 가 전용 키로 지킨다.
            request.requestURI.startsWith(WorkerAuthFilter.PREFIX) ||
            request.method.equals("OPTIONS", ignoreCase = true) ||
            request.requestURI in openPaths

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = sessionToken(request)
        val email = authService.emailOf(token)
        if (email == null) return deny(response, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        if (email != DemoContext.EMAIL) return chain.doFilter(request, response)
        // 데모는 허용 목록에 있는 것만 부를 수 있다. 새 엔드포인트가 생겨도 데모에는 닫혀 있다.
        if (!demoAllows(request)) {
            return deny(
                response,
                HttpStatus.FORBIDDEN,
                "데모에서는 쓸 수 없는 기능입니다. Gmail·Slack 같은 개인 계정 연동과 실제 자동화 실행은 관리자 로그인에서만 동작합니다.",
            )
        }
        // 세션 토큰을 그대로 두면 로그에 샐 수 있어 해시를 센다. 세션별 실행 횟수 카운트에만 쓴다.
        DemoContext.set(AuthService.hash(token!!))
        try {
            chain.doFilter(request, response)
        } finally {
            DemoContext.clear()
        }
    }

    private fun deny(response: HttpServletResponse, status: HttpStatus, detail: String) {
        response.status = status.value()
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"detail":"$detail"}""")
    }

    private fun demoAllows(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        // 읽음 표시는 id 가 가변이라 접두·접미로 본다. 나머지는 정확히 일치하는 것만 통과시킨다.
        if (request.method == "PATCH" && uri.startsWith("/api/ai-news/") && uri.endsWith("/read")) return true
        // 아래 둘은 id 가 가변이라 접두·접미로 본다. 통과는 "부를 수 있다"이지 "볼 수 있다"가 아니다.
        // 실제 격리는 toon_image.owner 조건이 한다(DemoContext 로 서버가 정한다).
        if (request.method == "GET" && uri.startsWith("/api/toon-images/")) return true
        if (request.method == "POST" && uri.startsWith("/api/instagram-toons/") && uri.endsWith("/images")) return true
        return "${request.method} $uri" in DEMO_ALLOWED
    }

    companion object {
        const val COOKIE = "office_session"

        /**
         * 데모가 부를 수 있는 전부. 읽기와 AI 생성·수집만 연다(생성은 진짜로 실행된다).
         * 여기 없는 것은 막힌다: 자동화 워커, 인스타툰, 업무·승인, Slack 연결, 초안 Slack 재알림.
         */
        private val DEMO_ALLOWED = setOf(
            "GET /api/auth/me",
            "POST /api/auth/logout",
            "GET /api/dashboard",
            "GET /api/content-packages",
            "POST /api/content-packages",
            "GET /api/ai-news",
            "POST /api/ai-news/refresh",
            "GET /api/ai-news/briefing",
            "POST /api/ai-news/briefing/refresh",
            "GET /api/ai-operations",
            "GET /api/topic-drafts",
            "POST /api/topic-drafts/refresh",
            "GET /api/slack/status",
            // 인스타툰은 모델을 한 번 부르고 문서 저장소에 남긴다. 파이썬 프로세스를 띄우지 않는다.
            "GET /api/instagram-toons",
            "POST /api/instagram-toons",
        )

        /** 화면이 로그인 여부를 즉시 알기 위한 표시용 쿠키. 인증 판단에는 쓰지 않는다. */
        const val HINT_COOKIE = "office_session_hint"

        fun sessionToken(request: HttpServletRequest): String? =
            request.cookies?.firstOrNull { it.name == COOKIE }?.value
    }
}
