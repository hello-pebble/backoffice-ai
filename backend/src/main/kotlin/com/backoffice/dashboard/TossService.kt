package com.backoffice.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class TossService(private val properties: OfficeProperties, private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(TossService::class.java)
    private val client = RestClient.builder().baseUrl(properties.toss.baseUrl).build()
    private val names = mapOf("005930" to "삼성전자", "000660" to "SK하이닉스", "373220" to "LG에너지솔루션")

    fun overview(): StockOverview {
        if (!properties.toss.enabled) return StockOverview(false, "토스증권 연동이 비활성화되어 있습니다.")
        if (properties.toss.clientId.isBlank() || properties.toss.clientSecret.isBlank()) {
            return StockOverview(false, "토스증권 Open API 자격증명이 아직 설정되지 않았습니다.")
        }
        return try {
            val token = client.post().uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=${properties.toss.clientId}&client_secret=${properties.toss.clientSecret}")
                .retrieve().body(JsonNode::class.java)?.path("access_token")?.asText().orEmpty()
            require(token.isNotBlank()) { "토스증권 액세스 토큰을 받지 못했습니다." }
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
}
