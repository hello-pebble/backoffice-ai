package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 호환 API와 Ollama 호출을 한 곳으로 모은 클라이언트.
 * 타임아웃·재시도·오류 문구 정규화·모델별 단가를 여기서만 관리한다.
 * 서비스는 프롬프트와 응답 해석에만 신경 쓴다.
 */
@Service
class LlmClient(private val properties: OfficeProperties, private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(LlmClient::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** 호출 전에 제공자·모델·주소를 알아야 실패도 같은 값으로 기록할 수 있다. */
    fun target(): LlmTarget {
        val useOllama = properties.aiNews.summaryProvider.equals("ollama", ignoreCase = true)
        val endpoint = if (useOllama) "${properties.aiNews.ollamaBaseUrl.trim().trimEnd('/')}/api/generate"
        else chatCompletionsUrl(properties.aiNews.openAiBaseUrl)
        val model = if (useOllama) properties.aiNews.ollamaModel else properties.aiNews.summaryModel
        return LlmTarget(useOllama, endpoint, model, vendorOf(useOllama, endpoint))
    }

    /** 도구 목록 표기도 제공자마다 같은 문구를 쓰도록 여기서 만든다. */
    fun toolLabel(target: LlmTarget): String = if (target.useOllama) "Ollama 로컬 API" else "${target.vendor} Chat Completions"

    /**
     * 한 번의 대화 요청. 설정 문제는 IllegalArgumentException, 그 밖의 실패는
     * 정규화한 사유를 담은 IllegalStateException 으로 올린다.
     */
    fun chat(system: String, user: String, jsonMode: Boolean = true): LlmResponse {
        val target = target()
        val key = properties.aiNews.openAiApiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" }
        if (!target.useOllama) require(key.isNotBlank()) { "OpenAI API 키가 설정되지 않았습니다. config/dashboard.properties에 office.ai-news.open-ai-api-key를 설정하거나 office.ai-news.summary-provider=ollama로 변경하세요." }
        val body = if (target.useOllama) objectMapper.writeValueAsString(
            buildMap {
                put("model", target.model)
                put("prompt", "$system\n\n$user")
                put("stream", false)
                if (jsonMode) put("format", "json")
            }
        ) else objectMapper.writeValueAsString(
            buildMap {
                put("model", target.model)
                put("messages", listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user)))
                if (jsonMode) put("response_format", mapOf("type" to "json_object"))
            }
        )
        val request = HttpRequest.newBuilder(URI(target.endpoint))
            .timeout(Duration.ofSeconds(properties.llm.requestTimeoutSeconds))
            .header("Content-Type", "application/json")
            .apply { if (!target.useOllama) header("Authorization", "Bearer $key") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = send(request)
        val json = objectMapper.readTree(response)
        val content = if (target.useOllama) json.path("response").asText()
        else json.path("choices").firstOrNull()?.path("message")?.path("content")?.asText()
        if (content.isNullOrBlank()) throw IllegalStateException("모델 응답이 비어 있습니다.")
        val inputTokens = if (target.useOllama) json.path("prompt_eval_count").asLong(0) else json.path("usage").path("prompt_tokens").asLong(0)
        val outputTokens = if (target.useOllama) json.path("eval_count").asLong(0) else json.path("usage").path("completion_tokens").asLong(0)
        return LlmResponse(content, inputTokens, outputTokens, target, costOf(target, inputTokens, outputTokens))
    }

    // 429·5xx·연결 실패만 다시 시도한다. 4xx 는 다시 보내도 같은 답이라 바로 올린다.
    // 타임아웃도 재시도하지 않는다. 이미 제한 시간을 다 쓴 요청이라 반복하면 대기만 3배가 되고,
    // 그 사이 브라우저는 이미 끊긴다. 실제로 120초 × 3회 = 6분을 매달린 실행이 있었다.
    private fun send(request: HttpRequest): String {
        var attempt = 1
        while (true) {
            val failure = try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) return response.body()
                val error = IllegalStateException("${response.statusCode()} 응답: ${response.body().take(300)}")
                if (response.statusCode() != 429 && response.statusCode() < 500) throw error
                error
            } catch (error: java.net.http.HttpTimeoutException) {
                throw IllegalStateException(
                    "모델이 ${properties.llm.requestTimeoutSeconds}초 안에 응답하지 않았습니다. " +
                        "더 빠른 모델을 쓰거나 office.llm.request-timeout-seconds 를 늘리세요.",
                    error,
                )
            } catch (error: java.io.IOException) {
                error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("모델 호출이 중단되었습니다.", error)
            }
            if (attempt >= properties.llm.maxAttempts) throw IllegalStateException(reasonOf(failure), failure)
            log.warn("모델 호출 재시도 {}/{}: {}", attempt, properties.llm.maxAttempts, reasonOf(failure))
            Thread.sleep(properties.llm.retryDelayMillis * (1L shl (attempt - 1)))
            attempt++
        }
    }

    private fun costOf(target: LlmTarget, inputTokens: Long, outputTokens: Long): Double =
        if (target.useOllama) 0.0 else estimateCostUsd(target.model, inputTokens, outputTokens)

    /**
     * 모델별 단가표에 있으면 그 값을, 없으면 기존 기본 단가를 쓴다.
     * 워커(Python)가 돌려준 사용량도 같은 표로 환산해야 비용이 한 기준으로 모인다.
     */
    fun estimateCostUsd(model: String, inputTokens: Long, outputTokens: Long): Double {
        // 단가표 키도 정규화한 이름으로 찾는다. 예전 설정이 원문 이름으로 적혀 있으면 그것도 받는다.
        val configured = (properties.llm.prices[canonicalModel(model)] ?: properties.llm.prices[model])
            ?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        val input = configured?.getOrNull(0) ?: properties.aiNews.inputPricePerMillionUsd
        val output = configured?.getOrNull(1) ?: properties.aiNews.outputPricePerMillionUsd
        return inputTokens * input / 1_000_000 + outputTokens * output / 1_000_000
    }

    companion object {
        /**
         * 기록·단가표용 모델 이름. 같은 모델이 "OpenAI/GPT-4o", "gpt-4o " 처럼 다르게 적히면
         * 운영 센터에서 두 줄로 갈라지고 단가도 못 찾는다. 제공자에 보내는 이름은 바꾸지 않는다.
         */
        fun canonicalModel(name: String): String = name.trim().substringAfterLast('/').lowercase()

        // 주소가 깨져 있으면 URI 파싱부터 터진다. 그 경우에도 설정값을 그대로 보여 줘야
        // 어디가 잘못됐는지 알 수 있으므로 host 추출 실패는 전체 문자열로 대체한다.
        fun vendorOf(useOllama: Boolean, endpoint: String): String =
            if (useOllama) "Ollama 로컬" else runCatching { URI(endpoint).host }.getOrNull() ?: endpoint

        // 연결 실패·타임아웃·잘못된 주소는 message 가 비거나 값만 담겨 원인을 알 수 없다.
        // 우리가 던진 예외가 아니면 예외 종류까지 남긴다.
        fun reasonOf(error: Throwable): String = when (error) {
            is IllegalArgumentException, is IllegalStateException -> error.message ?: error.toString()
            else -> "${error::class.simpleName}: ${error.message ?: "상세 메시지 없음"}"
        }

        // 설정값은 OpenAI SDK 의 base_url 과 같은 규칙으로, 버전 경로까지 포함한다고 본다.
        // 다만 호스트만 적어 넣는 실수가 잦아 경로가 비어 있을 때만 /v1 을 붙여 준다.
        // 이걸 안 하면 https://integrate.api.nvidia.com/v1 이 /v1/v1/... 이 돼 404 가 난다.
        fun chatCompletionsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            val path = runCatching { URI(base).path }.getOrNull().orEmpty()
            return if (path.isEmpty()) "$base/v1/chat/completions" else "$base/chat/completions"
        }
    }
}

data class LlmTarget(val useOllama: Boolean, val endpoint: String, val model: String, val vendor: String)
data class LlmResponse(val content: String, val inputTokens: Long, val outputTokens: Long, val target: LlmTarget, val costUsd: Double)
