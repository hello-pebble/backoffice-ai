package com.backoffice.dashboard.content

import com.backoffice.dashboard.*
import com.backoffice.dashboard.operations.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 원본 하나를 받아 체크된 채널마다 그 채널의 실제 에이전트를 돌린다.
 * 인스타툰·쇼츠는 기존 서비스가 만들고 각자 목록에도 저장한다(이미지 생성·Slack 검토 흐름 그대로).
 * 블로그는 워커 발행 큐(automation_content, 검토 대기)에 넣는다. 카드뉴스는 여기서 모델을 한 번 부른다.
 *
 * 4채널이면 1분을 넘겨 요청 스레드에서 기다리지 않는다(toon-image 와 같은 방식).
 * 요청은 모든 채널을 '생성중'으로 저장하고 바로 202 로 끝나고, 백그라운드 스레드가 채널을 하나씩 채운다.
 * 한 채널이 실패해도 나머지는 계속 돌고, 화면은 목록 API 를 폴링해 채널별 상태를 본다.
 */
@Service
class ContentStudioService(
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
    private val documents: JsonDocumentStore,
    private val instagramToonService: InstagramToonService,
    private val topicDraftService: TopicDraftService,
    private val automationRepository: AutomationRepository,
    private val llm: LlmClient,
    private val pool: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "content-studio").apply { isDaemon = true }
    },
) {
    private val availableChannels = setOf("인스타툰", "유튜브 쇼츠", "카드뉴스", "블로그")

    fun create(request: CreateContentPackageRequest): ContentPackage {
        require(request.source.trim().length >= 20) { "원본 콘텐츠를 20자 이상 입력하세요." }
        val channels = request.channels.filter { it in availableChannels }.distinct()
        require(channels.isNotEmpty()) { "만들 콘텐츠 채널을 하나 이상 선택하세요." }
        val source = request.source.trim()
        val title = source.replace(Regex("\\s+"), " ").take(34).trimEnd(' ', '.', '。')
        val tone = request.tone.ifBlank { "공감형" }
        val target = request.target.ifBlank { "관심 고객" }
        // ThreadLocal 은 백그라운드 스레드로 따라가지 않는다. 요청 스레드에 있는 지금 꺼내 둔다.
        val sessionKey = DemoContext.sessionKey()
        val packageItem = ContentPackage(
            UUID.randomUUID().toString(), title, source, tone, target, OffsetDateTime.now().toString(),
            channels.map { ContentOutput(it, title, "", status = PENDING) },
        )
        synchronized(this) { save((listOf(packageItem) + list()).take(30)) }
        pool.submit { runChannels(packageItem.id, sessionKey, channels, title, source, tone, target) }
        return packageItem
    }

    fun list(): List<ContentPackage> = documents.readList("content-packages", ContentPackage::class.java)

    /**
     * 재시작 전 '생성중'이던 채널은 실패로만 표시한다. 텍스트 생성은 이미지와 달리 사용자가 다시 누르는 게 싸다.
     * ponytail: 자동 재개 없음. 필요해지면 ToonImageService.resumeOrphans 처럼 원본을 다시 pool 에 넣으면 된다.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun failOrphans() = synchronized(this) {
        val items = list()
        if (items.none { item -> item.outputs.any { it.status == PENDING } }) return
        save(items.map { item ->
            item.copy(outputs = item.outputs.map { if (it.status == PENDING) it.copy(status = "실패", error = "서버 재시작으로 중단됐습니다. 다시 생성하세요.") else it })
        })
    }

    private fun runChannels(packageId: String, sessionKey: String?, channels: List<String>, title: String, source: String, tone: String, target: String) {
        // 여기서 DemoContext 를 다시 켜지 않으면 데모 방문자의 결과와 기록이 주인 데이터에 섞인다.
        sessionKey?.let { DemoContext.set(it) }
        try {
            for (channel in channels) {
                val output = runCatching { run(channel, title, source, tone, target) }
                    .getOrElse { ContentOutput(channel, title, "", status = "실패", error = LlmClient.reasonOf(it)) }
                // 채널 하나만 갈아 끼운다. 그 사이 다른 요청이 목록을 바꿨을 수 있어 매번 다시 읽는다.
                synchronized(this) {
                    save(list().map { item ->
                        if (item.id != packageId) item
                        else item.copy(outputs = item.outputs.map { if (it.channel == channel) output else it })
                    })
                }
            }
        } finally {
            DemoContext.clear()
        }
    }

    private fun run(channel: String, title: String, source: String, tone: String, target: String): ContentOutput = when (channel) {
        "인스타툰" -> instagramToonService.generate(CreateInstagramToonRequest(episode = source, tone = tone, panelCount = 4)).let { toon ->
            val panels = toon.panels.joinToString("\n") { "${it.number}컷 · ${it.scene}\n  ${it.dialogue}" }
            ContentOutput(channel, toon.title, "${toon.caption}\n\n$panels", refId = toon.id)
        }
        "유튜브 쇼츠" -> topicDraftService.draftFromText(title, source).let {
            ContentOutput(channel, it.title, "[훅] ${it.hook}\n\n${it.script}\n\n${it.hashtags.joinToString(" ") { tag -> "#$tag" }}", refId = it.id)
        }
        "카드뉴스" -> generate(channel, "당신은 핵심만 남기는 한국어 카드뉴스 편집자입니다.", """다음 원본을 6장짜리 카드뉴스로 구성하세요. 원본에 없는 사실은 만들지 마세요.
톤=$tone, 대상=$target
반드시 JSON만 반환하세요: {"title":"제목","cards":[{"number":1,"headline":"한 줄 헤드라인","body":"두세 문장"}]}.

원본=$source""") { node ->
            val cards = node.path("cards").joinToString("\n\n") { "${it.path("number").asInt()}장. ${it.path("headline").asText()}\n${it.path("body").asText()}" }
            check(cards.isNotBlank()) { "카드뉴스 응답에 cards 가 없습니다." }
            ContentOutput(channel, node.path("title").asText(title), cards)
        }
        else -> generate(channel, "당신은 사실을 과장하지 않는 한국어 블로그 작가입니다.", """다음 원본을 바탕으로 블로그 글을 쓰세요. 원본에 없는 수치·사실은 만들지 마세요.
본문은 공백 포함 1500자 이상, 소제목을 넣습니다. 톤=$tone, 대상=$target
반드시 JSON만 반환하세요: {"title":"제목","content":"본문","tags":["태그"]}.

원본=$source""") { node ->
            val body = node.path("content").asText("")
            check(body.isNotBlank()) { "블로그 응답에 content 가 없습니다." }
            val blogTitle = node.path("title").asText(title)
            val tags = node.path("tags").map { it.asText() }.filter { it.isNotBlank() }
            // 데모는 워커 발행 큐에 넣지 않는다. 주인 콘텐츠와 섞이면 안 된다.
            val id = if (DemoContext.isDemo()) null else UUID.randomUUID().toString().also {
                automationRepository.saveContent(SaveContentRequest(id = it, keyword = blogTitle, title = blogTitle, content = body, tags = tags, status = "pending"))
            }
            ContentOutput(channel, blogTitle, body, refId = id)
        }
    }

    /** 카드뉴스·블로그처럼 전용 서비스가 없는 채널. 모델 한 번, 운영 센터 기록 한 번. */
    private fun generate(channel: String, system: String, prompt: String, build: (JsonNode) -> ContentOutput): ContentOutput {
        val startedAt = System.nanoTime()
        val target = llm.target()
        val agent = "콘텐츠 생성 에이전트 · $channel"
        val tools = listOf("콘텐츠 스튜디오 원본", llm.toolLabel(target))
        try {
            val response = llm.chat(system, prompt)
            val output = build(LlmClient.jsonOf(objectMapper, response.content))
            aiOperationsService.record(
                agent = agent, provider = target.vendor, model = target.model, tools = tools,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                inputTokens = response.inputTokens, outputTokens = response.outputTokens, estimatedCostUsd = response.costUsd,
                resultPreview = "${output.title} · 초안을 저장했습니다.",
            )
            return output
        } catch (error: Exception) {
            aiOperationsService.record(
                agent = agent, provider = target.vendor, model = target.model, tools = tools,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000, status = "실패", error = "${target.endpoint} → ${LlmClient.reasonOf(error)}",
            )
            throw IllegalStateException("$channel 생성에 실패했습니다: ${LlmClient.reasonOf(error)}", error)
        }
    }

    private fun save(items: List<ContentPackage>) = documents.write("content-packages", items)

    companion object {
        const val PENDING = "생성중"
    }
}

data class CreateContentPackageRequest(val source: String = "", val tone: String = "공감형", val target: String = "", val channels: List<String> = emptyList())
data class ContentPackage(val id: String, val title: String, val source: String, val tone: String, val target: String, val createdAt: String, val outputs: List<ContentOutput>)
/** status: 생성중 → 성공 | 실패. refId: 인스타툰 id·초안 id·블로그 큐 id. 화면이 "어느 섹션에서 이어서 하는지" 안내할 때 쓴다. */
data class ContentOutput(val channel: String, val title: String, val body: String, val status: String = "성공", val error: String? = null, val refId: String? = null)
