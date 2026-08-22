package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ContentStudioService(private val objectMapper: ObjectMapper, private val aiOperationsService: AiOperationsService) {
    private val path = Path.of("data/content-studio/packages.json")
    private val availableChannels = setOf("인스타툰", "유튜브 쇼츠", "카드뉴스", "블로그")

    @Synchronized
    fun create(request: CreateContentPackageRequest): ContentPackage {
        val startedAt = System.nanoTime()
        require(request.source.trim().length >= 20) { "원본 콘텐츠를 20자 이상 입력하세요." }
        val channels = request.channels.filter { it in availableChannels }.distinct()
        require(channels.isNotEmpty()) { "만들 콘텐츠 채널을 하나 이상 선택하세요." }
        val source = request.source.trim()
        val title = source.replace(Regex("\\s+"), " ").take(34).trimEnd(' ', '.', '。')
        val packageItem = ContentPackage(
            id = UUID.randomUUID().toString(),
            title = title,
            source = source,
            tone = request.tone.ifBlank { "공감형" },
            target = request.target.ifBlank { "관심 고객" },
            createdAt = OffsetDateTime.now().toString(),
            outputs = channels.map { channel -> ContentOutput(channel, channelTitle(channel, title), draft(channel, title, source, request.target)) },
        )
        save((listOf(packageItem) + list()).take(30))
        aiOperationsService.record(
            agent = "콘텐츠 재활용 에이전트",
            provider = "콘텐츠 스튜디오",
            model = "초안 템플릿",
            tools = listOf("원본 콘텐츠 분석", *channels.toTypedArray()),
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            resultPreview = "${channels.joinToString(" · ")} 초안 ${channels.size}개를 만들었습니다.",
        )
        return packageItem
    }

    @Synchronized fun list(): List<ContentPackage> = if (Files.exists(path)) objectMapper.readValue(path.toFile(), objectMapper.typeFactory.constructCollectionType(List::class.java, ContentPackage::class.java)) else emptyList()

    private fun save(items: List<ContentPackage>) { Files.createDirectories(path.parent); objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), items) }
    private fun channelTitle(channel: String, title: String) = when (channel) { "인스타툰" -> "$title, 공감 4컷"; "유튜브 쇼츠" -> "$title | 45초 쇼츠"; "카드뉴스" -> "$title | 핵심 6장"; else -> "$title | 블로그 초안" }
    private fun draft(channel: String, title: String, source: String, target: String): String = when (channel) {
        "인스타툰" -> "1컷: 오늘도 시작된 이야기\n2컷: $title\n3컷: \"이거, 나만 그런가?\"\n4컷: ${target}도 고개를 끄덕일 공감 포인트\n\n원본 핵심: ${source.take(220)}"
        "유튜브 쇼츠" -> "[0~3초 훅] $title\n[4~25초] ${source.take(180)}\n[26~40초] 핵심은 한 가지입니다.\n[41~45초] 도움이 됐다면 다음 콘텐츠도 확인하세요.\n\n자막 키워드: 공감 · 핵심 · 실천"
        "카드뉴스" -> "1장. $title\n2장. 왜 지금 이 이야기가 중요한가\n3장. ${source.take(100)}\n4장. 우리가 놓치기 쉬운 포인트\n5장. 바로 적용할 한 가지\n6장. 저장하고 다음 콘텐츠에서 이어보세요"
        else -> "# $title\n\n## 한 줄 요약\n${source.take(180)}\n\n## 핵심 포인트\n- 문제 상황\n- 원인과 관찰\n- 바로 적용할 행동\n\n## 마무리\n${target}에게 필요한 다음 행동을 제안합니다."
    }
}

data class CreateContentPackageRequest(val source: String = "", val tone: String = "공감형", val target: String = "", val channels: List<String> = emptyList())
data class ContentPackage(val id: String, val title: String, val source: String, val tone: String, val target: String, val createdAt: String, val outputs: List<ContentOutput>)
data class ContentOutput(val channel: String, val title: String, val body: String)
