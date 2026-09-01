package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class AiNewsBriefingService(
    private val aiNewsService: AiNewsService,
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
    private val documents: JsonDocumentStore,
    private val llm: LlmClient,
) {
    fun get(): AiNewsBriefing? = documents.read("ai-news-briefing", AiNewsBriefing::class.java)

    // 실패도 AI 운영 센터에 남긴다. 화면이 "운영 센터에서 확인하세요"라고 안내하는데
    // 성공만 기록하면 정작 볼 게 없다. 어느 주소로 보냈는지도 함께 남겨야 원인을 좁힐 수 있다.
    @Synchronized fun refresh(): AiNewsBriefing {
        val startedAt = System.nanoTime()
        val target = llm.target()
        return try {
            generate(startedAt, target)
        } catch (error: Exception) {
            val reason = LlmClient.reasonOf(error)
            aiOperationsService.record(
                agent = "AI 뉴스 브리핑 에이전트",
                provider = target.vendor,
                model = target.model,
                tools = listOf("AI 뉴스 저장소", llm.toolLabel(target)),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                status = "실패",
                error = "${target.endpoint} → $reason",
            )
            // 설정·입력 문제(400)는 그대로 두고, 나머지는 주소를 붙여 502 로 올린다.
            // 연결 실패나 잘못된 주소는 예외 메시지만으로 원인을 알 수 없다.
            if (error is IllegalArgumentException) throw error
            throw IllegalStateException("AI 요약에 실패했습니다 (${target.endpoint}): $reason", error)
        }
    }

    private fun generate(startedAt: Long, target: LlmTarget): AiNewsBriefing {
        val selected = aiNewsService.list().sortedByDescending { importance(it) }.take(3)
        require(selected.size >= 3) { "AI 소식을 먼저 수집한 뒤 요약하세요." }
        val newsText = selected.mapIndexed { index, item -> "${index + 1}. id=${item.id}\n제목=${item.title}\n출처=${item.source}\n내용=${item.summary}" }.joinToString("\n\n")
        val prompt = """다음 AI 뉴스 3건을 한국 CEO가 1분 안에 읽을 수 있도록 요약하세요.
각 항목은 원문 내용만 근거로 2문장 이내의 한국어 요약과, 우리 업무에 왜 중요한지 한 문장으로 작성합니다.
반드시 JSON만 반환하세요: {"items":[{"id":"뉴스 id","summary":"한국어 요약","impact":"업무 영향"}]}.

$newsText"""
        val response = llm.chat("당신은 사실을 과장하지 않는 한국어 AI 산업 분석가입니다.", prompt)
        val summaries = runCatching { objectMapper.readTree(response.content).path("items").map { AiNewsSummary(it.path("id").asText(), it.path("summary").asText(), it.path("impact").asText()) } }
            .getOrElse { throw IllegalStateException("${if (target.useOllama) "로컬 모델" else "AI 모델"}이 요약 형식을 올바르게 만들지 못했습니다. 더 큰 모델을 사용하거나 다시 시도하세요.") }
        require(summaries.size == 3) { "AI가 3건 요약을 반환하지 않았습니다." }
        return AiNewsBriefing(OffsetDateTime.now().toString(), target.model, selected, summaries).also {
            save(it)
            aiOperationsService.record(
                agent = "AI 뉴스 브리핑 에이전트",
                provider = target.vendor,
                model = target.model,
                tools = listOf("AI 뉴스 저장소", llm.toolLabel(target)),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens,
                estimatedCostUsd = response.costUsd,
                resultPreview = summaries.joinToString(" / ") { summary -> summary.summary },
            )
        }
    }

    private fun importance(item: AiNewsItem): Int = when (item.category) { "모델" -> 5; "에이전트" -> 4; "이미지·영상" -> 3; "연구·안전" -> 2; else -> 1 }
    private fun save(briefing: AiNewsBriefing) = documents.write("ai-news-briefing", briefing)
}

data class AiNewsBriefing(val generatedAt: String, val model: String, val news: List<AiNewsItem>, val items: List<AiNewsSummary>)
data class AiNewsSummary(val id: String, val summary: String, val impact: String)
