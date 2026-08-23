package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

@Service
class AiNewsBriefingService(private val properties: OfficeProperties, private val aiNewsService: AiNewsService, private val objectMapper: ObjectMapper, private val aiOperationsService: AiOperationsService, private val documents: JsonDocumentStore) {
    private val client = HttpClient.newHttpClient()
    private val path get() = Path.of(properties.aiNews.briefingPath)

    fun get(): AiNewsBriefing? = documents.read("ai-news-briefing", AiNewsBriefing::class.java)

    @Synchronized fun refresh(): AiNewsBriefing {
        val startedAt = System.nanoTime()
        val useOllama = properties.aiNews.summaryProvider.equals("ollama", ignoreCase = true)
        val key = properties.aiNews.openAiApiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" }
        if (!useOllama) require(key.isNotBlank()) { "OpenAI API 키가 설정되지 않았습니다. config/dashboard.properties에 office.ai-news.open-ai-api-key를 설정하거나 office.ai-news.summary-provider=ollama로 변경하세요." }
        val chatUrl = chatCompletionsUrl(properties.aiNews.openAiBaseUrl)
        val vendor = if (useOllama) "Ollama 로컬" else URI(chatUrl).host
        val selected = aiNewsService.list().sortedByDescending { importance(it) }.take(3)
        require(selected.size >= 3) { "AI 소식을 먼저 수집한 뒤 요약하세요." }
        val newsText = selected.mapIndexed { index, item -> "${index + 1}. id=${item.id}\n제목=${item.title}\n출처=${item.source}\n내용=${item.summary}" }.joinToString("\n\n")
        val prompt = """다음 AI 뉴스 3건을 한국 CEO가 1분 안에 읽을 수 있도록 요약하세요.
각 항목은 원문 내용만 근거로 2문장 이내의 한국어 요약과, 우리 업무에 왜 중요한지 한 문장으로 작성합니다.
반드시 JSON만 반환하세요: {"items":[{"id":"뉴스 id","summary":"한국어 요약","impact":"업무 영향"}]}.

$newsText"""
        val body = if (useOllama) objectMapper.writeValueAsString(mapOf("model" to properties.aiNews.ollamaModel, "prompt" to "당신은 사실을 과장하지 않는 한국어 AI 산업 분석가입니다.\n\n$prompt", "stream" to false, "format" to "json")) else objectMapper.writeValueAsString(mapOf("model" to properties.aiNews.summaryModel, "messages" to listOf(mapOf("role" to "system", "content" to "당신은 사실을 과장하지 않는 한국어 AI 산업 분석가입니다."), mapOf("role" to "user", "content" to prompt)), "response_format" to mapOf("type" to "json_object")))
        val requestBuilder = HttpRequest.newBuilder(URI(if (useOllama) "${properties.aiNews.ollamaBaseUrl.trimEnd('/')}/api/generate" else chatUrl)).header("Content-Type", "application/json")
        if (!useOllama) requestBuilder.header("Authorization", "Bearer $key")
        val request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("AI 요약 요청에 실패했습니다: ${response.body().take(300)}")
        val responseJson = objectMapper.readTree(response.body())
        val content = if (useOllama) responseJson.path("response").asText() else responseJson.path("choices").firstOrNull()?.path("message")?.path("content")?.asText()
        if (content.isNullOrBlank()) throw IllegalStateException("AI 요약 응답이 비어 있습니다.")
        val summaries = runCatching { objectMapper.readTree(content).path("items").map { AiNewsSummary(it.path("id").asText(), it.path("summary").asText(), it.path("impact").asText()) } }
            .getOrElse { throw IllegalStateException("${if (useOllama) "로컬 모델" else "AI 모델"}이 요약 형식을 올바르게 만들지 못했습니다. 더 큰 모델을 사용하거나 다시 시도하세요.") }
        require(summaries.size == 3) { "AI가 3건 요약을 반환하지 않았습니다." }
        val inputTokens = if (useOllama) responseJson.path("prompt_eval_count").asLong(0) else responseJson.path("usage").path("prompt_tokens").asLong(0)
        val outputTokens = if (useOllama) responseJson.path("eval_count").asLong(0) else responseJson.path("usage").path("completion_tokens").asLong(0)
        val cost = if (useOllama) 0.0 else inputTokens * properties.aiNews.inputPricePerMillionUsd / 1_000_000 + outputTokens * properties.aiNews.outputPricePerMillionUsd / 1_000_000
        val model = if (useOllama) properties.aiNews.ollamaModel else properties.aiNews.summaryModel
        return AiNewsBriefing(OffsetDateTime.now().toString(), model, selected, summaries).also {
            save(it)
            aiOperationsService.record(
                agent = "AI 뉴스 브리핑 에이전트",
                provider = vendor,
                model = model,
                tools = listOf("AI 뉴스 저장소", if (useOllama) "Ollama 로컬 API" else "$vendor Chat Completions"),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = cost,
                resultPreview = summaries.joinToString(" / ") { summary -> summary.summary },
            )
        }
    }

    companion object {
        // 설정값은 OpenAI SDK 의 base_url 과 같은 규칙으로, 버전 경로까지 포함한다고 본다.
        // 다만 호스트만 적어 넣는 실수가 잦아 경로가 비어 있을 때만 /v1 을 붙여 준다.
        // 이걸 안 하면 https://integrate.api.nvidia.com/v1 이 /v1/v1/... 이 돼 404 가 난다.
        fun chatCompletionsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            val path = runCatching { URI(base).path }.getOrNull().orEmpty()
            return if (path.isEmpty()) "$base/v1/chat/completions" else "$base/chat/completions"
        }
    }

    private fun importance(item: AiNewsItem): Int = when (item.category) { "모델" -> 5; "에이전트" -> 4; "이미지·영상" -> 3; "연구·안전" -> 2; else -> 1 }
    private fun save(briefing: AiNewsBriefing) = documents.write("ai-news-briefing", briefing)
}

data class AiNewsBriefing(val generatedAt: String, val model: String, val news: List<AiNewsItem>, val items: List<AiNewsSummary>)
data class AiNewsSummary(val id: String, val summary: String, val impact: String)
