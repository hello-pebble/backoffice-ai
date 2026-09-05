package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.exp

/**
 * RSS 소식에서 우선순위가 가장 높은 주제 하나를 골라 숏폼 검토 대본 초안을 만든다.
 * RSS 에는 조회수·좋아요 같은 인기 지표가 없으므로 "최신성 + 카테고리 관련성" 우선순위로만 고른다.
 * Slack 에는 대본 전문이 아니라 알림과 검토 링크만 보내고, 전송이 실패해도 초안 저장은 성공한다.
 */
@Service
class TopicDraftService(
    private val properties: OfficeProperties,
    private val aiNewsService: AiNewsService,
    private val automationRepository: AutomationRepository,
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
    private val documents: JsonDocumentStore,
    private val llm: LlmClient,
    private val slack: SlackService,
) {

    fun list(): List<TopicDraft> = load().sortedByDescending { it.createdAt }

    @Synchronized
    fun refresh(): TopicDraft {
        val startedAt = System.nanoTime()
        val target = llm.target()
        val now = OffsetDateTime.now()
        val news = aiNewsService.refresh()
        val newsCandidate = selectCandidate(news, load().map { it.sourceId }.toSet(), now)?.let { DraftSource.fromNews(it, now) }
        // 우선순위·검색량이 높은 키워드 하나만 후보로 본다. 이미 automationRepository 가 그 순서로 정렬해 준다.
        // 데모는 실제 automation_keyword 를 읽지도, used=true 로 바꾸지도 않는다(뉴스 후보만 쓴다).
        val keyword = if (DemoContext.isDemo()) null else automationRepository.unusedKeywords(1).firstOrNull()
        val keywordCandidate = keyword?.let { DraftSource.fromKeyword(it) }
        val candidate = listOfNotNull(newsCandidate, keywordCandidate).maxByOrNull { it.priorityScore }
            ?: throw IllegalArgumentException("초안으로 만들 새 주제가 없습니다. 소식원이나 키워드가 갱신된 뒤 다시 시도하세요.")
        val (usage, script) = try {
            generate(candidate, target)
        } catch (error: Exception) {
            val reason = LlmClient.reasonOf(error)
            aiOperationsService.record(
                agent = "주제 대본 초안 에이전트",
                provider = target.vendor,
                model = target.model,
                tools = listOf(candidate.toolLabel, llm.toolLabel(target)),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                status = "실패",
                error = "${target.endpoint} → $reason",
            )
            // 자리표시자 대본은 저장하지 않는다. 검토자가 진짜 초안으로 오해한다.
            if (error is IllegalArgumentException) throw error
            throw IllegalStateException("대본 초안 생성에 실패했습니다 (${target.endpoint}): $reason", error)
        }
        val draft = persist(candidate, script, target.model, now)
        // 같은 키워드로 두 번 대본을 만들지 않도록, 성공했을 때만 사용 처리한다.
        if (candidate.keyword != null) {
            automationRepository.saveKeyword(SaveKeywordRequest(id = candidate.keyword.id, used = true, priority = candidate.keyword.priority))
        }
        aiOperationsService.record(
            agent = "주제 대본 초안 에이전트",
            provider = target.vendor,
            model = target.model,
            tools = listOf(candidate.toolLabel, llm.toolLabel(target), "Slack 알림 · ${draft.slackStatus}"),
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            estimatedCostUsd = usage.costUsd,
            resultPreview = "${draft.title} · 검토 대기 초안을 저장했습니다.",
        )
        return draft
    }

    /** Slack 이 미설정이었거나 실패한 초안의 알림만 다시 보낸다. 대본은 그대로 둔다. */
    @Synchronized
    fun notify(id: String): TopicDraft {
        val drafts = load()
        val draft = drafts.firstOrNull { it.id == id } ?: throw IllegalArgumentException("초안을 찾을 수 없습니다.")
        require(draft.slackStatus != "SENT") { "이미 Slack 알림을 보낸 초안입니다." }
        val (status, error) = sendSlack(draft)
        val updated = draft.copy(slackStatus = status, slackError = error)
        save(drafts.map { if (it.id == id) updated else it })
        return updated
    }

    /** 초안 저장은 Slack 결과와 무관하게 성공한다. 알림 상태만 함께 기록해 둔다. */
    internal fun persist(candidate: DraftSource, script: TopicScript, model: String, now: OffsetDateTime): TopicDraft {
        val id = UUID.randomUUID().toString()
        val draft = TopicDraft(
            id = id,
            sourceId = candidate.sourceId,
            source = candidate.source,
            sourceTitle = candidate.sourceTitle,
            sourceUrl = candidate.sourceUrl,
            category = candidate.category,
            priorityScore = candidate.priorityScore,
            title = script.title,
            hook = script.hook,
            script = script.script,
            hashtags = script.hashtags,
            reviewStatus = "REVIEW_PENDING",
            slackStatus = "NOT_CONFIGURED",
            slackError = null,
            reviewUrl = reviewUrl(id),
            model = model,
            createdAt = now.toString(),
        )
        val (status, error) = sendSlack(draft)
        val saved = draft.copy(slackStatus = status, slackError = error)
        save((listOf(saved) + load()).take(50))
        return saved
    }

    // 대본 전문은 보내지 않는다. 알림 문구와 검토 링크만 보낸다.
    private fun sendSlack(draft: TopicDraft): Pair<String, String?> =
        slack.notify("새 검토 대본 초안이 준비됨: ${draft.title}\n검토 링크: ${draft.reviewUrl}")

    private fun reviewUrl(id: String): String = "${properties.slack.reviewBaseUrl.trim().trimEnd('/')}/#topic-draft-$id"

    private fun generate(source: DraftSource, target: LlmTarget): Pair<LlmResponse, TopicScript> {
        val prompt = """다음 내용 하나를 소개하는 한국어 숏폼 검토 대본을 작성하세요.
읽어서 45~60초 분량(공백 포함 550~750자)으로 씁니다.
${source.guardrail}
반드시 JSON만 반환하세요: {"title":"영상 제목","hook":"3초 훅 한 문장","script":"대본 전문","hashtags":["태그","태그"]}.

제목=${source.sourceTitle}
${source.context}"""
        val response = llm.chat("당신은 사실을 과장하지 않는 한국어 숏폼 대본 작가입니다.", prompt)
        return response to parseScript(response.content, if (target.useOllama) "로컬 모델" else "AI 모델")
    }

    private fun parseScript(content: String, modelLabel: String): TopicScript {
        // JSON 모드를 무시하고 설명 문장을 앞뒤에 붙이는 호환 제공자가 있다. 첫 중괄호 블록만 다시 시도한다.
        val node = runCatching { objectMapper.readTree(content) }
            .recoverCatching { objectMapper.readTree(content.substringAfter('{', "").substringBeforeLast('}', "").let { "{$it}" }) }
            .getOrElse { throw IllegalStateException("${modelLabel}이 JSON 형식을 만들지 못했습니다. 더 큰 모델을 쓰거나 다시 시도하세요.") }
        val title = node.path("title").asText("").trim()
        val hook = node.path("hook").asText("").trim()
        val script = node.path("script").asText("").trim()
        val hashtags = node.path("hashtags").map { it.asText("").trim() }.filter { it.isNotBlank() }
        if (title.isBlank() || hook.isBlank() || script.isBlank()) {
            throw IllegalStateException("대본 응답에 title·hook·script 가 모두 들어 있지 않습니다.")
        }
        return TopicScript(title, hook, script, hashtags)
    }

    private fun load(): List<TopicDraft> = documents.readList("topic-drafts", TopicDraft::class.java)
    private fun save(items: List<TopicDraft>) = documents.write("topic-drafts", items)

    companion object {
        private val CATEGORY_WEIGHT = mapOf("모델" to 5.0, "에이전트" to 4.0, "이미지·영상" to 3.0, "연구·안전" to 2.0)

        /** 카테고리 가중치 + 최신성 점수(48시간 감쇠). 인기 지표가 아니라 우선순위 점수다. */
        fun priorityScore(item: AiNewsItem, now: OffsetDateTime): Double {
            val weight = CATEGORY_WEIGHT[item.category] ?: 1.0
            val published = parseTime(item.publishedAt) ?: parseTime(item.collectedAt)
            val hours = published?.let { maxOf(0.0, Duration.between(it, now).toMinutes() / 60.0) } ?: 48.0
            return weight + 5.0 * exp(-hours / 48.0)
        }

        /** 이미 초안으로 만든 source id 는 제외한다. 같은 주제로 두 번 대본을 만들지 않는다. */
        fun selectCandidate(items: List<AiNewsItem>, usedSourceIds: Set<String>, now: OffsetDateTime): AiNewsItem? =
            items.filterNot { it.id in usedSourceIds }.maxByOrNull { priorityScore(it, now) }

        // ponytail: priority·search_volume 단위가 뉴스 카테고리 가중치(1~10)와 안 맞을 수 있는 임의 환산이다.
        // 실제 데이터로 어느 쪽이 계속 이기는지 보고 스케일을 조정하라.
        fun keywordPriorityScore(item: AutomationKeyword): Double =
            item.priority.toDouble() + (item.searchVolume / 1000.0).coerceIn(0.0, 5.0)

        // RFC 1123(pubDate)과 ISO 8601(Atom)이 섞여 들어온다. 못 읽으면 48시간 경과로 본다.
        private fun parseTime(value: String?): OffsetDateTime? {
            if (value.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(value) }
                .recoverCatching { OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) }
                .getOrNull()
        }
    }
}

/** 뉴스 기사와 자동화 키워드처럼 서로 다른 후보를 같은 방식으로 고르고 대본 프롬프트를 만들기 위한 공통 형태. */
data class DraftSource(
    val sourceId: String,
    val source: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val category: String,
    val priorityScore: Double,
    val guardrail: String,
    val context: String,
    val toolLabel: String,
    val keyword: AutomationKeyword? = null,
) {
    companion object {
        private const val NEWS_GUARDRAIL = "아래 제목·요약·주소에 있는 내용만 근거로 삼고, 원문에 없는 수치·기능·출시일·추측은 만들지 마세요."
        private const val KEYWORD_GUARDRAIL = "키워드와 관련해 일반적으로 알려진 사실만 사용하고, 확인되지 않는 수치·통계·최신 소식은 만들지 마세요."

        fun fromNews(item: AiNewsItem, now: OffsetDateTime): DraftSource = DraftSource(
            sourceId = item.id,
            source = item.source,
            sourceTitle = item.title,
            sourceUrl = item.url,
            category = item.category,
            priorityScore = TopicDraftService.priorityScore(item, now),
            guardrail = NEWS_GUARDRAIL,
            context = "요약=${item.summary}\n주소=${item.url}\n출처=${item.source}",
            toolLabel = "AI 뉴스 수집",
        )

        fun fromKeyword(item: AutomationKeyword): DraftSource = DraftSource(
            sourceId = "keyword-${item.id}",
            source = "키워드 수집",
            sourceTitle = item.keyword,
            sourceUrl = "",
            category = item.category,
            priorityScore = TopicDraftService.keywordPriorityScore(item),
            guardrail = KEYWORD_GUARDRAIL,
            context = "키워드=${item.keyword}\n카테고리=${item.category}",
            toolLabel = "키워드 수집",
            keyword = item,
        )
    }
}

data class TopicScript(
    val title: String,
    val hook: String,
    val script: String,
    val hashtags: List<String>,
)

data class TopicDraft(
    val id: String,
    val sourceId: String,
    val source: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val category: String,
    val priorityScore: Double,
    val title: String,
    val hook: String,
    val script: String,
    val hashtags: List<String>,
    val reviewStatus: String,
    val slackStatus: String,
    val slackError: String? = null,
    val reviewUrl: String,
    val model: String,
    val createdAt: String,
)
