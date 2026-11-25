package com.backoffice.dashboard.auth

import com.backoffice.dashboard.DemoContext
import com.backoffice.dashboard.OfficeProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class AuthController(
    private val authService: AuthService,
    private val properties: OfficeProperties,
) {
    @GetMapping("/auth/login")
    // 브라우저 내비게이션에는 쿠키·헤더를 붙일 수 없어 302 대신 주소를 돌려주고 화면에서 이동한다.
    fun login(): Map<String, String> = try {
        mapOf("url" to authService.authorizationUrl())
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @GetMapping("/auth/callback")
    fun authCallback(code: String?, state: String?): ResponseEntity<String> {
        val result = if (code.isNullOrBlank() || state.isNullOrBlank()) LoginResult(null, "로그인 정보가 올바르지 않습니다.")
        else authService.completeLogin(code, state)
        val token = result.token
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Content-Type", "text/html; charset=utf-8")
                .body(page(result.error ?: "로그인하지 못했습니다."))
        // Max-Age 를 주지 않는 브라우저 세션 쿠키. 브라우저를 완전히 닫으면 로그아웃된다.
        // 안 닫고 계속 쓰는 경우는 서버 세션 만료(office.auth.session-hours)가 상한이다.
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, maxAgeSeconds = null).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("1", maxAgeSeconds = null).toString())
            .header(HttpHeaders.LOCATION, properties.auth.successRedirect)
            .body("")
    }

    @GetMapping("/auth/me")
    fun me(request: HttpServletRequest): Map<String, Any> {
        val email = authService.emailOf(SessionAuthFilter.sessionToken(request))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        return mapOf("email" to email, "demo" to (email == DemoContext.EMAIL))
    }

    /** 로그인 없이 둘러보기. 예약 이메일로 세션을 만들어 화면이 로그인 상태로 동작하게 한다. */
    @PostMapping("/auth/demo")
    fun demoLogin(): ResponseEntity<Map<String, Boolean>> = try {
        val token = authService.startDemoSession()
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, maxAgeSeconds = null).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("1", maxAgeSeconds = null).toString())
            .body(mapOf("ok" to true))
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Map<String, Boolean>> {
        authService.logout(SessionAuthFilter.sessionToken(request))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("", 0).toString())
            .body(mapOf("ok" to true))
    }

    private fun sessionCookie(value: String, maxAgeSeconds: Long?) =
        cookie(SessionAuthFilter.COOKIE, value, maxAgeSeconds, httpOnly = true)

    /**
     * 세션 쿠키는 HttpOnly 라 화면이 읽을 수 없다. 그래서 로그인 여부를 알려면 /api/auth/me 왕복을
     * 기다려야 하고, 그동안 새로고침마다 로그인 카드가 깜빡였다.
     * 값이 없는 힌트 쿠키를 하나 더 줘서 화면이 첫 줄에서 바로 판단하게 한다.
     * 이 쿠키는 인증에 쓰이지 않는다. 위조해도 서버는 세션 쿠키만 본다.
     */
    private fun hintCookie(value: String, maxAgeSeconds: Long?) =
        cookie(SessionAuthFilter.HINT_COOKIE, value, maxAgeSeconds, httpOnly = false)

    /** maxAgeSeconds 가 null 이면 디스크에 남지 않는 브라우저 세션 쿠키가 된다. 로그아웃은 0 으로 지운다. */
    private fun cookie(name: String, value: String, maxAgeSeconds: Long?, httpOnly: Boolean): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(httpOnly).path("/").apply { maxAgeSeconds?.let { maxAge(it) } }
            .secure(properties.auth.cookieSecure).sameSite(properties.auth.cookieSameSite).build()

    private fun page(message: String) = "<!doctype html><html lang=\"ko\"><body><p>$message</p></body></html>"
}
