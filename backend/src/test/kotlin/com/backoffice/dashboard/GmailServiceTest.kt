package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 자격증명을 파일이 아닌 환경변수로 넣는 배포 경로를 지킨다.
 * 파일 존재만 확인하는 코드가 하나라도 남으면 배포본에서 연결이 시작되지 않는데,
 * 로컬에는 파일이 있어서 로컬 테스트로는 절대 드러나지 않는다.
 */
class GmailServiceTest {

    private val clientJson = """
        {"web":{"client_id":"test-client-id","client_secret":"test-secret",
        "auth_uri":"https://accounts.google.com/o/oauth2/auth",
        "token_uri":"https://oauth2.googleapis.com/token",
        "redirect_uris":["http://127.0.0.1:8765/api/gmail/callback"]}}
    """.trimIndent()

    private fun service(gmail: OfficeProperties.Gmail) =
        GmailService(OfficeProperties(gmail = gmail), PostgresDataStoreFactory(FakeDocumentStore()))

    @Test
    fun `자격증명이 환경변수로만 있어도 인증 URL을 만든다`() {
        val gmail = OfficeProperties.Gmail(
            enabled = true,
            credentialsJson = clientJson,
            credentialsPath = "존재하지-않는-경로/gmail-credentials.json",
        )

        val url = service(gmail).authorizationUrl()

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/auth"), "실제 URL: $url")
        assertTrue(url.contains("client_id=test-client-id"), "실제 URL: $url")
        assertTrue(url.contains("gmail.readonly"), "요청 스코프가 빠졌다: $url")
    }

    @Test
    fun `파일도 환경변수도 없으면 인증 URL 생성이 거부된다`() {
        val gmail = OfficeProperties.Gmail(enabled = true, credentialsPath = "존재하지-않는-경로/x.json")

        val error = assertFailsWith<IllegalArgumentException> { service(gmail).authorizationUrl() }
        assertEquals("Gmail OAuth 자격증명이 설정되지 않았습니다.", error.message)
    }

    @Test
    fun `연동이 꺼져 있으면 자격증명이 있어도 호출하지 않는다`() {
        val gmail = OfficeProperties.Gmail(enabled = false, credentialsJson = clientJson)

        val overview = service(gmail).overview()

        assertFalse(overview.connected)
        assertEquals("Gmail 연동이 비활성화되어 있습니다.", overview.message)
    }
}
