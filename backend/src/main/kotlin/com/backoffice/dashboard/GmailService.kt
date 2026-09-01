package com.backoffice.dashboard

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service
class GmailService(private val properties: OfficeProperties, private val tokenStore: PostgresDataStoreFactory) {
    private val log = LoggerFactory.getLogger(GmailService::class.java)
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val states = ConcurrentHashMap<String, Boolean>()
    private val userId = "office-dashboard-user"
    private val scope = listOf("https://www.googleapis.com/auth/gmail.readonly")

    fun overview(): GmailOverview {
        if (!properties.gmail.enabled) return GmailOverview(false, "Gmail 연동이 비활성화되어 있습니다.")
        if (!hasCredentials()) return GmailOverview(false, "Gmail OAuth 자격증명이 설정되지 않았습니다.")
        return try {
            val credential = flow().loadCredential(userId) ?: return GmailOverview(false, "Gmail 연결이 아직 완료되지 않았습니다.")
            val gmail = gmail(credential)
            // 라벨의 안 읽음 수는 홍보·소셜까지 세서 "확인할 메일"보다 늘 크다. 같은 기준으로 세고 보여 준다.
            val found = gmail.users().messages().list("me").setQ(properties.gmail.query).setMaxResults(COUNT_LIMIT).execute().messages.orEmpty()
            val messages = found.take(5).map { message ->
                val detail = gmail.users().messages().get("me", message.id).setFormat("metadata").setMetadataHeaders(listOf("From", "Subject", "Date")).execute()
                val headers = detail.payload.headers.associate { it.name.lowercase() to it.value }
                MailItem(headers["from"] ?: "(보낸사람 없음)", headers["subject"] ?: "(제목 없음)", headers["date"] ?: "")
            }
            GmailOverview(true, unread = found.size, messages = messages, more = found.size >= COUNT_LIMIT)
        } catch (error: Exception) {
            log.warn("Gmail 개요 조회 실패", error)
            GmailOverview(false, "Gmail을 불러오지 못했습니다.")
        }
    }

    fun authorizationUrl(): String {
        require(hasCredentials()) { "Gmail OAuth 자격증명이 설정되지 않았습니다." }
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        states[state] = true
        // approval_prompt=force: 구글은 같은 클라이언트+계정의 최초 동의에서만 리프레시 토큰을 준다.
        // 강제하지 않으면 재동의 시 액세스 토큰만 받아 약 1시간 뒤 끊기고, 재시작마다 재인증이 필요해진다.
        return flow().newAuthorizationUrl()
            .setRedirectUri(redirectUri())
            .setState(state)
            .setApprovalPrompt("force")
            .build()
    }

    fun completeAuthorization(code: String, state: String): Boolean {
        if (states.remove(state) != true) return false
        val flow = flow()
        val token = flow.newTokenRequest(code).setRedirectUri(redirectUri()).execute()
        flow.createAndStoreCredential(token, userId)
        return true
    }

    private fun gmail(credential: Credential) = Gmail.Builder(transport, jsonFactory, credential).setApplicationName("Office Dashboard").build()
    private fun flow(): GoogleAuthorizationCodeFlow =
        GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, clientSecrets(), scope)
            .setDataStoreFactory(tokenStore).setAccessType("offline").build()

    /** 배포에서는 환경변수(JSON 문자열), 로컬에서는 파일을 쓴다. 환경변수가 우선한다. */
    private fun clientSecrets(): GoogleClientSecrets {
        val inline = properties.gmail.credentialsJson
        if (inline.isNotBlank()) return StringReader(inline).use { GoogleClientSecrets.load(jsonFactory, it) }
        require(Files.exists(credentialsPath())) { "Gmail OAuth 자격증명이 설정되지 않았습니다." }
        return Files.newBufferedReader(credentialsPath()).use { GoogleClientSecrets.load(jsonFactory, it) }
    }

    companion object {
        // ponytail: 개수는 이 상한까지만 정확하다. 그 이상은 화면이 "50+"로 보여 준다.
        // 정확한 총계가 필요해지면 페이지를 끝까지 넘겨야 하는데, 지금 쓰임에는 과하다.
        private const val COUNT_LIMIT = 50L
    }

    private fun hasCredentials() = properties.gmail.credentialsJson.isNotBlank() || Files.exists(credentialsPath())
    private fun redirectUri() = properties.gmail.redirectUri
    private fun credentialsPath() = Path.of(properties.gmail.credentialsPath)
}
