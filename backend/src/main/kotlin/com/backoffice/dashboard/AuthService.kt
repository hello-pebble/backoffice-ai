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
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
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

    /**
     * 가짜 로그인. 예약된 이메일로 진짜 세션을 하나 만든다. 그 뒤로는 앱 전체가 로그인 상태로 동작하고,
     * SessionAuthFilter 가 이 이메일을 보고 데모 격리(문서 키 네임스페이스 + 허용 목록)를 건다.
     */
    @Synchronized
    fun startDemoSession(): String {
        require(properties.demo.enabled) { "데모 모드가 꺼져 있습니다." }
        // 인증이 꺼져 있으면 세션 필터가 아예 돌지 않아 격리도 걸리지 않는다. 그 환경에서는 데모를 열지 않는다.
        require(properties.auth.enabled) { "인증이 꺼진 환경에서는 데모를 열 수 없습니다." }
        seedDemoDocuments()
        val token = randomToken()
        val expiresAt = OffsetDateTime.now().plusHours(properties.demo.sessionHours)
        save(activeSessions() + AuthSession(hash(token), DemoContext.EMAIL, expiresAt.toString()))
        return token
    }

    /**
     * 데모 문서가 없을 때만 씨앗을 넣는다. 방문자가 만든 초안·실행 이력은 그대로 쌓여 보여 줄 게 많아진다.
     * 씨앗을 고쳐 다시 넣고 싶으면 delete from app_document where document_key like 'demo:%' 한 줄이면 된다.
     * 이 함수는 공개 경로(필터 밖)에서 돌아 DemoContext 가 꺼져 있으므로 접두사를 직접 붙인다.
     */
    private fun seedDemoDocuments() = DEMO_SEED_KEYS.forEach { key ->
        val target = DemoContext.KEY_PREFIX + key
        if (documents.read(target, com.fasterxml.jackson.databind.JsonNode::class.java) != null) return@forEach
        val json = javaClass.getResourceAsStream("/demo/$key.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@forEach
        // 씨앗의 날짜는 넣는 날 기준으로 채운다. 고정 날짜로 두면 다음 날 방문자에게 "오늘 실행 0" 으로 보인다.
        val today = java.time.LocalDate.now()
        val filled = json.replace("{{today}}", today.toString()).replace("{{today-4}}", today.minusDays(4).toString())
        documents.write(target, objectMapper.readTree(filled))
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

    // 데모 방문자도 이 목록에 세션을 남긴다. 50 이면 방문 50회 만에 주인 세션이 밀려나 로그아웃된다.
    private fun save(sessions: List<AuthSession>) = documents.write("auth-sessions", sessions.takeLast(500))

    companion object {
        /** 데모 보드의 시작 내용. 실데이터를 가공해 넣어 둔 resources 의 demo 폴더 JSON 파일 이름과 같다. */
        private val DEMO_SEED_KEYS = listOf("ai-news", "ai-news-briefing", "topic-drafts", "content-packages", "ai-operations")

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
