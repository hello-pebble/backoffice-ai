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
            val unread = gmail.users().labels().get("me", "INBOX").execute().messagesUnread ?: 0
            val messages = gmail.users().messages().list("me").setLabelIds(listOf("INBOX")).setMaxResults(5).execute().messages.orEmpty().map { message ->
                val detail = gmail.users().messages().get("me", message.id).setFormat("metadata").setMetadataHeaders(listOf("From", "Subject", "Date")).execute()
                val headers = detail.payload.headers.associate { it.name.lowercase() to it.value }
                MailItem(headers["from"] ?: "(보낸사람 없음)", headers["subject"] ?: "(제목 없음)", headers["date"] ?: "")
            }
            GmailOverview(true, unread = unread, messages = messages)
        } catch (error: Exception) {
            log.warn("Gmail 개요 조회 실패", error)
            GmailOverview(false, "Gmail을 불러오지 못했습니다.")
        }
    }

    fun authorizationUrl(): String {
        require(Files.exists(credentialsPath())) { "Gmail OAuth 설정 파일이 없습니다." }
        val stateBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
        states[state] = true
        return flow().newAuthorizationUrl().setRedirectUri(redirectUri()).setState(state).build()
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

    private fun hasCredentials() = properties.gmail.credentialsJson.isNotBlank() || Files.exists(credentialsPath())
    private fun redirectUri() = properties.gmail.redirectUri
    private fun credentialsPath() = Path.of(properties.gmail.credentialsPath)
}
