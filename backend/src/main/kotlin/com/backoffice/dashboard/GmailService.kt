package com.backoffice.dashboard

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.services.gmail.Gmail
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Gmail 메일함 조회. 인증은 여기서 하지 않는다.
 * 구글 로그인(AuthService)이 gmail.readonly 동의까지 받아 같은 저장소에 토큰을 넣어 준다.
 */
@Service
class GmailService(private val properties: OfficeProperties, private val tokenStore: PostgresDataStoreFactory) {
    private val log = LoggerFactory.getLogger(GmailService::class.java)
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val scope = listOf("https://www.googleapis.com/auth/gmail.readonly")

    /**
     * 메일함은 초 단위로 정확할 필요가 없다. 한 번 조회에 구글로 왕복이 6번(목록 1 + 상세 5) 나가므로
     * 성공한 결과만 잠깐 재사용한다. 실패는 캐시하지 않는다. 연동을 막 붙였는데 "연동 없음"이
     * 1분 동안 남아 있으면 고장으로 보인다.
     */
    fun overview(): GmailOverview {
        cached?.let { (at, value) -> if (Duration.between(at, Instant.now()) < CACHE_TTL) return value }
        return load().also { if (it.connected) cached = Instant.now() to it }
    }

    @Volatile
    private var cached: Pair<Instant, GmailOverview>? = null

    private fun load(): GmailOverview {
        if (!properties.gmail.enabled) return GmailOverview(false, "Gmail 연동이 비활성화되어 있습니다.")
        val secrets = AuthService.clientSecrets(properties) ?: return GmailOverview(false, "Gmail OAuth 자격증명이 설정되지 않았습니다.")
        return try {
            val flow = GoogleAuthorizationCodeFlow.Builder(transport, jsonFactory, secrets, scope)
                .setDataStoreFactory(tokenStore).build()
            val credential = flow.loadCredential(USER_ID)
                ?: return GmailOverview(false, "Gmail 연동이 아직 없습니다. Google 로그인을 다시 하면 연결됩니다.")
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

    private fun gmail(credential: Credential) = Gmail.Builder(transport, jsonFactory, credential).setApplicationName("Office Dashboard").build()

    companion object {
        /** 로그인(AuthService)이 토큰을 저장하고 여기서 읽는 공용 키. */
        const val USER_ID = "office-dashboard-user"

        // ponytail: 개수는 이 상한까지만 정확하다. 그 이상은 화면이 "50+"로 보여 준다.
        // 정확한 총계가 필요해지면 페이지를 끝까지 넘겨야 하는데, 지금 쓰임에는 과하다.
        private const val COUNT_LIMIT = 50L

        // ponytail: 프로세스 안에만 두는 캐시다. 인스턴스가 여러 개면 각자 갖는다.
        // 지금은 서버가 하나라 문제되지 않는다. 여러 대가 되면 공용 저장소로 옮겨야 한다.
        private val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}
