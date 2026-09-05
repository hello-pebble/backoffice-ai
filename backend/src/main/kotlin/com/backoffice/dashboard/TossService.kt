package com.backoffice.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant

@Service
class TossService(private val properties: OfficeProperties, private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(TossService::class.java)
    private val client = RestClient.builder().baseUrl(properties.toss.baseUrl).build()
    private val names = mapOf("005930" to "삼성전자", "000660" to "SK하이닉스", "373220" to "LG에너지솔루션")

    @Volatile
    private var cached: Pair<Instant, StockOverview>? = null

    @Volatile
    private var cachedToken: Pair<Instant, String>? = null

    /**
     * 시세는 초 단위로 정확할 필요가 없다. 성공한 결과만 잠깐 재사용해 새로고침마다
     * 외부로 나가는 걸 막는다. 실패는 캐시하지 않는다. 자격증명을 고친 직후 바로 보여야 한다.
     */
    fun overview(): StockOverview {
        cached?.let { (at, value) -> if (Duration.between(at, Instant.now()) < CACHE_TTL) return value }
        return load().also { if (it.connected) cached = Instant.now() to it }
    }

    private fun load(): StockOverview {
        if (!properties.toss.enabled) return StockOverview(false, "토스증권 연동이 비활성화되어 있습니다.")
        if (properties.toss.clientId.isBlank() || properties.toss.clientSecret.isBlank()) {
            return StockOverview(false, "토스증권 Open API 자격증명이 아직 설정되지 않았습니다.")
        }
        return try {
            val token = accessToken()
            val prices = client.get().uri { builder -> builder.path("/api/v1/prices").queryParam("symbols", properties.toss.watchlist.joinToString(",")).build() }
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body(JsonNode::class.java)?.path("result")
            val items = prices?.map { item ->
                val symbol = item.path("symbol").asText()
                StockItem(symbol, names[symbol] ?: symbol, item.path("lastPrice").asText(), item.path("currency").asText("KRW"), item.path("timestamp").asText(null))
            }.orEmpty()
            StockOverview(true, items = items)
        } catch (error: Exception) {
            log.warn("토스증권 시세 조회 실패", error)
            StockOverview(false, "토스증권 시세를 불러오지 못했습니다.")
        }
    }

    /** 토큰은 만료 전까지 다시 쓴다. 시세를 부를 때마다 발급받으면 왕복이 매번 두 번이 된다. */
    private fun accessToken(): String {
        cachedToken?.let { (expiresAt, value) -> if (Instant.now().isBefore(expiresAt)) return value }
        val response = client.post().uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials&client_id=${properties.toss.clientId}&client_secret=${properties.toss.clientSecret}")
            .retrieve().body(JsonNode::class.java)
        val token = response?.path("access_token")?.asText().orEmpty()
        require(token.isNotBlank()) { "토스증권 액세스 토큰을 받지 못했습니다." }
        // expires_in 이 없으면 짧게 잡는다. 만료된 토큰을 오래 붙들면 401 이 반복된다.
        val seconds = response?.path("expires_in")?.asLong(0)?.takeIf { it > 0 } ?: 600
        cachedToken = Instant.now().plusSeconds(maxOf(seconds - 60, 30)) to token
        return token
    }

    companion object {
        // ponytail: 프로세스 안에만 두는 캐시다. 서버가 여러 대가 되면 공용 저장소로 옮겨야 한다.
        private val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}
