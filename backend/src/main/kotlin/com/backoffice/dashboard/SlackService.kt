package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Slack 앱을 OAuth 로 설치하고 봇 토큰으로 chat.postMessage 를 보낸다.
 * 설치가 안 됐거나 채널을 안 골랐으면 알림은 NOT_CONFIGURED 로 남고, 부르는 쪽 작업은 계속 성공해야 한다.
 *
 * Slack API 는 실패도 HTTP 200 에 {"ok":false,"error":"..."} 로 준다. 상태 코드만 보면 성공으로 오인한다.
 */
@Service
class SlackService(private val properties: OfficeProperties, private val objectMapper: ObjectMapper, private val documents: JsonDocumentStore) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val states = ConcurrentHashMap<String, Boolean>()
    private val scopes = "chat:write,chat:write.public,channels:read,groups:read"

    fun status(): SlackStatus {
        val connection = connection()
        return SlackStatus(
            connected = connection != null,
            configured = properties.slack.clientId.isNotBlank() && properties.slack.clientSecret.isNotBlank(),
            teamName = connection?.teamName,
            channelId = connection?.channelId,
            channelName = connection?.channelName,
            connectedAt = connection?.connectedAt,
        )
    }

    fun installUrl(): String {
        require(properties.slack.clientId.isNotBlank() && properties.slack.clientSecret.isNotBlank()) {
            "Slack 앱 자격증명(office.slack.client-id/client-secret)이 설정되지 않았습니다."
        }
        val state = AuthService.randomToken()
        states[state] = true
        return "https://slack.com/oauth/v2/authorize?client_id=${encode(properties.slack.clientId)}" +
            "&scope=${encode(scopes)}&redirect_uri=${encode(properties.slack.redirectUri)}&state=$state"
    }

    @Synchronized
    fun completeInstall(code: String, state: String): Boolean {
        if (states.remove(state) != true) return false
        val form = mapOf(
            "client_id" to properties.slack.clientId,
            "client_secret" to properties.slack.clientSecret,
            "code" to code,
            "redirect_uri" to properties.slack.redirectUri,
        ).map { (key, value) -> "${encode(key)}=${encode(value)}" }.joinToString("&")
        val response = runCatching { post("oauth.v2.access", form) }.getOrNull() ?: return false
        if (!response.path("ok").asBoolean(false)) return false
        val token = response.path("access_token").asText("")
        if (token.isBlank()) return false
        documents.write(
            "slack-connection",
            SlackConnection(
                botToken = token,
                teamName = response.path("team").path("name").asText(""),
                channelId = null,
                channelName = null,
                connectedAt = OffsetDateTime.now().toString(),
            ),
        )
        return true
    }

    /** 봇이 접근할 수 있는 채널 목록. 설치 직후 화면에서 하나를 고르게 한다. */
    fun channels(): List<SlackChannel> {
        val connection = connection() ?: throw IllegalArgumentException("Slack 이 아직 연결되지 않았습니다.")
        val response = get("conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=200", connection.botToken)
        if (!response.path("ok").asBoolean(false)) {
            throw IllegalStateException("Slack 채널 목록을 가져오지 못했습니다: ${response.path("error").asText("알 수 없는 오류")}")
        }
        return response.path("channels").map { SlackChannel(it.path("id").asText(), it.path("name").asText()) }
    }

    @Synchronized
    fun selectChannel(channelId: String): SlackStatus {
        val connection = connection() ?: throw IllegalArgumentException("Slack 이 아직 연결되지 않았습니다.")
        val channel = channels().firstOrNull { it.id == channelId }
            ?: throw IllegalArgumentException("고른 채널을 찾을 수 없습니다. 봇이 접근할 수 있는 채널인지 확인하세요.")
        documents.write("slack-connection", connection.copy(channelId = channel.id, channelName = channel.name))
        return status()
    }

    /** 알림 결과를 (상태, 사유)로 돌려준다. 예외를 던지지 않는 건 부르는 쪽 작업을 막지 않기 위해서다. */
    fun notify(text: String): Pair<String, String?> {
        val connection = connection() ?: return "NOT_CONFIGURED" to null
        val channel = connection.channelId ?: return "NOT_CONFIGURED" to null
        return try {
            val body = objectMapper.writeValueAsString(mapOf("channel" to channel, "text" to text))
            val response = postJson("chat.postMessage", body, connection.botToken)
            if (response.path("ok").asBoolean(false)) "SENT" to null
            else "FAILED" to "Slack 오류: ${response.path("error").asText("알 수 없는 오류")}"
        } catch (error: Exception) {
            "FAILED" to LlmClient.reasonOf(error)
        }
    }

    private fun connection(): SlackConnection? = documents.read("slack-connection", SlackConnection::class.java)

    private fun url(method: String) = "${properties.slack.apiBaseUrl.trim().trimEnd('/')}/$method"

    private fun post(method: String, form: String) = send(
        HttpRequest.newBuilder(URI(url(method))).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
    )

    private fun postJson(method: String, body: String, token: String) = send(
        HttpRequest.newBuilder(URI(url(method))).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    )

    private fun get(method: String, token: String) = send(
        HttpRequest.newBuilder(URI(url(method))).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token").GET().build()
    )

    private fun send(request: HttpRequest) =
        objectMapper.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body())

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

data class SlackConnection(
    val botToken: String,
    val teamName: String,
    val channelId: String?,
    val channelName: String?,
    val connectedAt: String,
)

/** 화면에 내려보내는 상태. 봇 토큰은 절대 포함하지 않는다. */
data class SlackStatus(
    val connected: Boolean,
    val configured: Boolean,
    val teamName: String?,
    val channelId: String?,
    val channelName: String?,
    val connectedAt: String?,
)

data class SlackChannel(val id: String, val name: String)
