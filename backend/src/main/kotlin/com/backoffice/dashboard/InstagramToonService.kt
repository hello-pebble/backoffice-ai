package com.backoffice.dashboard

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 에피소드 한 줄을 인스타툰 대본으로 바꾼다. 컷마다 장면·대사·나레이션과 이미지 프롬프트를 함께 만든다.
 *
 * 예전에는 파이썬 스크립트를 프로세스로 띄웠지만, 배포 이미지에 파이썬이 없어 실제로는 동작하지 않았다.
 * 모델을 한 번 부르고 JSON 을 받는 게 전부라 공용 LlmClient 로 옮겼다. 그 덕에 재시도·타임아웃·
 * 토큰과 비용 기록·데모 상한이 다른 기능과 같은 규칙으로 적용된다.
 * 결과는 문서 저장소에 넣어 데모 요청이면 자동으로 demo: 자리에 격리된다.
 */
@Service
class InstagramToonService(
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
    private val llm: LlmClient,
    private val documents: JsonDocumentStore,
) {
    fun generate(request: CreateInstagramToonRequest): InstagramToon {
        val startedAt = System.nanoTime()
        val episode = request.episode.trim()
        require(episode.length >= 10) { "에피소드는 10자 이상 입력하세요." }
        require(request.panelCount in setOf(4, 8)) { "컷 수는 4 또는 8만 가능합니다." }
        val tone = request.tone.ifBlank { "공감형" }
        val target = llm.target()

        val (usage, result) = try {
            val response = llm.chat(SYSTEM_PROMPT, prompt(episode, tone, request.panelCount))
            response to parse(response.content, request.panelCount)
        } catch (error: Exception) {
            aiOperationsService.record(
                agent = "인스타툰 대본 에이전트",
                provider = target.vendor,
                model = target.model,
                tools = listOf(llm.toolLabel(target)),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                status = "실패",
                error = "${target.endpoint} → ${LlmClient.reasonOf(error)}",
            )
            if (error is IllegalArgumentException) throw error
            throw IllegalStateException("인스타툰 대본 생성에 실패했습니다 (${target.endpoint}): ${LlmClient.reasonOf(error)}", error)
        }

        val toon = InstagramToon(
            id = UUID.randomUUID().toString(),
            episode = episode,
            tone = tone,
            panelCount = request.panelCount,
            title = result.title,
            caption = result.caption,
            hashtags = result.hashtags,
            panels = result.panels,
            createdAt = OffsetDateTime.now().toString(),
            model = target.model,
        )
        save((listOf(toon) + list()).take(20))
        aiOperationsService.record(
            agent = "인스타툰 대본 에이전트",
            provider = target.vendor,
            model = target.model,
            tools = listOf(llm.toolLabel(target), "${request.panelCount}컷 구성"),
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            estimatedCostUsd = usage.costUsd,
            resultPreview = "${toon.title} · ${toon.panelCount}컷 대본과 이미지 프롬프트를 만들었습니다.",
        )
        return toon
    }

    fun list(): List<InstagramToon> = documents.readList(KEY, InstagramToon::class.java)

    private fun save(items: List<InstagramToon>) = documents.write(KEY, items)

    private fun prompt(episode: String, tone: String, panelCount: Int) = """
        짧은 에피소드를 한국어 인스타그램 웹툰 제작용 대본으로 구성하세요.

        에피소드: $episode
        톤: $tone
        컷 수: $panelCount

        반드시 JSON 객체만 반환하세요. 마크다운은 쓰지 마세요.
        스키마:
        {"title":"15자 안팎의 제목","caption":"인스타그램 게시글 캡션","hashtags":["#태그"],
         "panels":[{"number":1,"scene":"장면·표정·구도","dialogue":"말풍선 대사","narration":"독백 또는 효과음",
         "image_prompt":"일관된 캐릭터가 유지되는 한국 웹툰 스타일의 영어 이미지 프롬프트"}]}

        규칙:
        - panels 배열은 정확히 ${panelCount}개입니다.
        - 일상 공감형 서사이며, 마지막 컷에 명확한 여운 또는 반전을 둡니다.
        - 대사는 짧고 읽기 쉽게 씁니다.
        - hashtags 는 8~12개입니다.
    """.trimIndent()

    /** 모델이 앞뒤에 설명을 붙이는 경우가 있어 첫 중괄호 블록만 다시 시도한다. TopicDraftService 와 같은 규칙이다. */
    private fun parse(content: String, panelCount: Int): ToonScript {
        val node = runCatching { objectMapper.readTree(content) }
            .recoverCatching { objectMapper.readTree("{" + content.substringAfter('{', "").substringBeforeLast('}', "") + "}") }
            .getOrElse { throw IllegalStateException("모델이 JSON 형식을 만들지 못했습니다. 더 큰 모델을 쓰거나 다시 시도하세요.") }
        val panels = node.path("panels").mapIndexed { index, panel ->
            InstagramToonPanel(
                number = panel.path("number").asInt(index + 1),
                scene = panel.path("scene").asText("").trim(),
                dialogue = panel.path("dialogue").asText("").trim(),
                narration = panel.path("narration").asText("").trim(),
                imagePrompt = panel.path("image_prompt").asText("").trim(),
            )
        }
        // 컷 수가 다르면 화면이 요청한 구성과 어긋난다. 자리표시자로 채우지 않고 실패로 돌린다.
        if (panels.size != panelCount) throw IllegalStateException("요청한 ${panelCount}컷과 다른 결과가 왔습니다. 다시 시도해 주세요.")
        val title = node.path("title").asText("").trim()
        if (title.isBlank()) throw IllegalStateException("대본 응답에 제목이 없습니다.")
        return ToonScript(
            title = title,
            caption = node.path("caption").asText("").trim(),
            hashtags = node.path("hashtags").map { it.asText("").trim() }.filter { it.isNotBlank() },
            panels = panels,
        )
    }

    companion object {
        private const val KEY = "instagram-toons"
        private const val SYSTEM_PROMPT = "당신은 인스타그램 웹툰 전문 작가이자 스토리보드 작가입니다."
    }
}

private data class ToonScript(
    val title: String,
    val caption: String,
    val hashtags: List<String>,
    val panels: List<InstagramToonPanel>,
)

data class CreateInstagramToonRequest(val episode: String = "", val tone: String = "공감형", val panelCount: Int = 4)

data class InstagramToon(
    val id: String,
    val episode: String,
    val tone: String,
    @JsonProperty("panel_count") val panelCount: Int,
    val title: String,
    val caption: String,
    val hashtags: List<String>,
    val panels: List<InstagramToonPanel>,
    @JsonProperty("created_at") val createdAt: String,
    val model: String? = null,
)

data class InstagramToonPanel(
    val number: Int,
    val scene: String,
    val dialogue: String,
    val narration: String,
    @JsonProperty("image_prompt") val imagePrompt: String,
)
