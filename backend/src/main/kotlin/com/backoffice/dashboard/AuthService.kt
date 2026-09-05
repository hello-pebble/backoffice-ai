package com.backoffice.dashboard

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.stereotype.Service
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Google 로그인. 자격증명은 Gmail 연동에 쓰는 클라이언트를 그대로 재사용한다(콘솔에 리디렉션 주소만 추가).
 *
 * 로그인 동의에 gmail.readonly 스코프를 함께 요청해, 한 번의 로그인으로 Gmail 연동까지 끝낸다.
 * 받은 토큰은 GmailService 가 읽는 저장소에 같은 키로 넣는다. 별도의 Gmail 연결 절차는 없다.
 *
 * 허용 목록에 없는 계정은 로그인시키지 않는다. 목록이 비면 아무도 못 들어온다(닫힌 기본값).
 * 세션 토큰은 원문 대신 해시로 저장한다. 저장소가 새도 그 값으로는 로그인할 수 없다.
 */
@Service
class AuthService(
    private val properties: OfficeProperties,
    private val documents: JsonDocumentStore,
    private val tokenStore: PostgresDataStoreFactory,
) {
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val states = ConcurrentHashMap<String, Boolean>()
    private val scopes = listOf("openid", "email", "profile", "https://www.googleapis.com/auth/gmail.readonly")

    fun authorizationUrl(): String {
        val flow = flow()
        require(flow != null) { "Google OAuth 자격증명이 설정되지 않았습니다." }
        require(properties.auth.allowedEmails.isNotEmpty()) { "로그인 허용 이메일(office.auth.allowed-emails)이 설정되지 않았습니다." }
        val state = randomToken()
        states[state] = true
        // approval_prompt=force: 구글은 최초 동의에서만 리프레시 토큰을 준다. 강제하지 않으면
        // 저장된 토큰이 회수·만료됐을 때(invalid_grant) 재로그인해도 새 토큰을 못 받아 영영 복구되지 않는다.
        return flow.newAuthorizationUrl().setRedirectUri(properties.auth.redirectUri)
            .setState(state).setApprovalPrompt("force").build()
    }

    /** 인증 코드를 세션으로 바꾸고, 응답에 리프레시 토큰이 있으면 Gmail 자격증명도 저장한다. */
    @Synchronized
    fun completeLogin(code: String, state: String): LoginResult {
        if (states.remove(state) != true) return LoginResult(null, "로그인 요청이 만료되었습니다. 다시 시도하세요.")
        val flow = flow() ?: return LoginResult(null, "Google OAuth 자격증명이 설정되지 않았습니다.")
        val response = runCatching {
            flow.newTokenRequest(code).setRedirectUri(properties.auth.redirectUri).execute()
        }.getOrNull()
        val email = runCatching { response?.parseIdToken()?.payload?.email }.getOrNull()
        if (email.isNullOrBlank()) return LoginResult(null, "Google 계정 정보를 확인하지 못했습니다.")
        if (!isAllowed(email)) return LoginResult(null, "$email 은(는) 허용된 계정이 아닙니다.")
        // 구글은 계정당 최초 동의에서만 리프레시 토큰을 준다. 없는 응답으로 덮어쓰면
        // 저장된 리프레시 토큰이 사라져 약 1시간 뒤 Gmail 이 끊긴다. 있을 때만 저장한다.
        if (!response!!.refreshToken.isNullOrBlank()) flow.createAndStoreCredential(response, GmailService.USER_ID)
        val token = randomToken()
        val expiresAt = OffsetDateTime.now().plusHours(properties.auth.sessionHours)
        save(activeSessions() + AuthSession(hash(token), email.lowercase(), expiresAt.toString()))
        return LoginResult(token, null, email.lowercase())
    }

    /** 요청 쿠키의 세션이 살아 있으면 이메일을, 아니면 null 을 돌려준다. */
    fun emailOf(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val hashed = hash(token)
        return activeSessions().firstOrNull { it.tokenHash == hashed }?.email
    }

    @Synchronized
    fun logout(token: String?) {
        if (token.isNullOrBlank()) return
        val hashed = hash(token)
        save(activeSessions().filterNot { it.tokenHash == hashed })
    }

    fun isAllowed(email: String): Boolean =
        properties.auth.allowedEmails.any { it.trim().equals(email.trim(), ignoreCase = true) }

    private fun flow(): GoogleAuthorizationCodeFlow? {
        val secrets = clientSecrets(properties) ?: return null
        return GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, secrets, scopes)
            .setDataStoreFactory(tokenStore).setAccessType("offline").build()
    }

    /** 만료된 세션은 읽는 김에 버린다. 따로 정리 작업을 둘 만큼 양이 많지 않다. */
    private fun activeSessions(): List<AuthSession> {
        val now = OffsetDateTime.now()
        return documents.readList("auth-sessions", AuthSession::class.java)
            .filter { runCatching { OffsetDateTime.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
    }

    private fun save(sessions: List<AuthSession>) = documents.write("auth-sessions", sessions.takeLast(50))

    companion object {
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** 배포에서는 환경변수(JSON 문자열), 로컬에서는 파일. Gmail 연동과 같은 클라이언트를 쓴다. */
        fun clientSecrets(properties: OfficeProperties): GoogleClientSecrets? {
            val jsonFactory = GsonFactory.getDefaultInstance()
            val inline = properties.gmail.credentialsJson
            if (inline.isNotBlank()) return runCatching { StringReader(inline).use { GoogleClientSecrets.load(jsonFactory, it) } }.getOrNull()
            val path = Path.of(properties.gmail.credentialsPath)
            if (!Files.exists(path)) return null
            return runCatching { Files.newBufferedReader(path).use { GoogleClientSecrets.load(jsonFactory, it) } }.getOrNull()
        }
    }
}

data class AuthSession(val tokenHash: String, val email: String, val expiresAt: String)
data class LoginResult(val token: String?, val error: String? = null, val email: String? = null)
