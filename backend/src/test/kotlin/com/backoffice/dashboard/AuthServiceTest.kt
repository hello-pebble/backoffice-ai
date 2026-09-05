package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val documents = FakeDocumentStore()

    private fun service(vararg allowed: String, credentialsJson: String = "") = AuthService(
        OfficeProperties(
            auth = OfficeProperties.Auth(allowedEmails = allowed.toList()),
            gmail = OfficeProperties.Gmail(credentialsJson = credentialsJson, credentialsPath = "존재하지-않는-경로/x.json"),
        ),
        documents,
        PostgresDataStoreFactory(documents),
    )

    private val clientJson = """
        {"web":{"client_id":"test-client-id","client_secret":"test-secret",
        "auth_uri":"https://accounts.google.com/o/oauth2/auth",
        "token_uri":"https://oauth2.googleapis.com/token",
        "redirect_uris":["http://127.0.0.1:8765/api/auth/callback"]}}
    """.trimIndent()

    @Test
    fun `로그인 동의가 Gmail 스코프와 리프레시 토큰 요청을 함께 담는다`() {
        val url = service("owner@example.com", credentialsJson = clientJson).authorizationUrl()

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/auth"), "실제 URL: $url")
        assertTrue(url.contains("client_id=test-client-id"), "실제 URL: $url")
        // 이 스코프가 빠지면 로그인은 되지만 Gmail 연동이 다시 별도 절차로 돌아간다.
        assertTrue(url.contains("gmail.readonly"), "Gmail 스코프가 빠졌다: $url")
        assertTrue(url.contains("access_type=offline"), "리프레시 토큰 요청이 빠졌다: $url")
    }

    @Test
    fun `허용 목록은 대소문자와 앞뒤 공백을 무시한다`() {
        val service = service(" Owner@Example.com ")

        assertEquals(true, service.isAllowed("owner@example.com"))
        assertEquals(true, service.isAllowed("OWNER@EXAMPLE.COM"))
        assertEquals(false, service.isAllowed("someone@example.com"))
    }

    @Test
    fun `허용 목록이 비면 아무도 로그인할 수 없다`() {
        val service = service()

        assertEquals(false, service.isAllowed("owner@example.com"))
        // 목록이 비었는데 로그인 주소를 내주면 아무나 동의 화면까지 가고 콜백에서야 막힌다.
        val error = assertFailsWith<IllegalArgumentException> { service.authorizationUrl() }
        assertEquals("Google OAuth 자격증명이 설정되지 않았습니다.", error.message)
    }

    @Test
    fun `세션 토큰은 원문이 아니라 해시로 저장된다`() {
        val token = AuthService.randomToken()
        documents.write("auth-sessions", listOf(session(AuthService.hash(token), "owner@example.com", plusHours = 1)))

        assertEquals("owner@example.com", service("owner@example.com").emailOf(token))
        // 저장소가 새더라도 그 값으로는 로그인할 수 없어야 한다.
        assertNotEquals(token, AuthService.hash(token))
        assertNull(service("owner@example.com").emailOf(AuthService.hash(token)))
    }

    @Test
    fun `만료된 세션과 없는 세션은 통과시키지 않는다`() {
        documents.write("auth-sessions", listOf(session(AuthService.hash("만료"), "owner@example.com", plusHours = -1)))
        val service = service("owner@example.com")

        assertNull(service.emailOf("만료"))
        assertNull(service.emailOf("모르는-토큰"))
        assertNull(service.emailOf(null))
        assertNull(service.emailOf(""))
    }

    @Test
    fun `로그아웃하면 그 세션만 사라진다`() {
        val mine = AuthService.randomToken()
        val other = AuthService.randomToken()
        documents.write("auth-sessions", listOf(
            session(AuthService.hash(mine), "owner@example.com", plusHours = 1),
            session(AuthService.hash(other), "owner@example.com", plusHours = 1),
        ))
        val service = service("owner@example.com")

        service.logout(mine)

        assertNull(service.emailOf(mine))
        assertEquals("owner@example.com", service.emailOf(other))
    }

    private fun session(hash: String, email: String, plusHours: Long) =
        AuthSession(hash, email, OffsetDateTime.now().plusHours(plusHours).toString())
}
