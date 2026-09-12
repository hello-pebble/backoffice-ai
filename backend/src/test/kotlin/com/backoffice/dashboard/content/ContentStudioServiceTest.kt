package com.backoffice.dashboard.content

import com.backoffice.dashboard.*
import com.backoffice.dashboard.operations.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 체크된 채널마다 그 채널의 에이전트를 부르고, 한 채널이 실패해도 나머지는 저장되는지 검증한다.
 * 인스타툰·쇼츠는 각 서비스 mock, 카드뉴스·블로그는 LlmClient mock 으로 돈다.
 * 백그라운드 풀 대신 즉시 실행 executor 를 넣어 create() 가 돌아올 때 채널이 이미 채워져 있게 한다.
 */
class ContentStudioServiceTest {
    private val documents = FakeDocumentStore()
    private val toons = mock(InstagramToonService::class.java)
    private val drafts = mock(TopicDraftService::class.java)
    private val automation = mock(AutomationRepository::class.java)
    private val llm = mock(LlmClient::class.java)
    private val service = build(inlineExecutor(runInline = true))
    private val source = "아침에 커피를 마시며 오늘 할 일을 정리하는 습관에 대한 이야기입니다."
    private val target = LlmTarget(useOllama = false, endpoint = "https://api.example.com/v1/chat/completions", model = "gpt-test", vendor = "api.example.com")

    private fun build(pool: ExecutorService) =
        ContentStudioService(ObjectMapper(), mock(AiOperationsService::class.java), documents, toons, drafts, automation, llm, pool)

    private fun toon() = InstagramToon("toon-1", source, "공감형", 4, "툰 제목", "캡션", listOf("#툰"),
        listOf(InstagramToonPanel(1, "장면", "대사", "독백", "prompt")), "2026-09-06T09:00:00+09:00", "gpt-test")
    private fun draft() = TopicDraft("draft-1", "studio-x", "콘텐츠 스튜디오", "제목", "", "스튜디오", 0.0,
        "쇼츠 제목", "훅", "대본", listOf("AI"), "REVIEW_PENDING", "NOT_CONFIGURED", null, "http://x/#topic-draft-draft-1", "gpt-test", "2026-09-06T09:00:00+09:00")

    private fun llmAnswers(content: String) {
        `when`(llm.target()).thenReturn(target)
        `when`(llm.chat(anyString(), anyString(), anyBoolean())).thenReturn(LlmResponse(content, 10, 20, target, 0.001))
    }

    private fun saved() = service.list().single()

    @Test
    fun `요청은 모든 채널을 생성중으로 저장하고 바로 돌아온다`() {
        val service = build(inlineExecutor(runInline = false))

        val result = service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "카드뉴스")))

        assertTrue(result.outputs.all { it.status == "생성중" })
        assertEquals(listOf(result.id), service.list().map { it.id })
        verify(llm, never()).chat(anyString(), anyString(), anyBoolean())
    }

    @Test
    fun `체크된 채널만 각자의 에이전트로 만들고 저장한다`() {
        `when`(toons.generate(anyArg())).thenReturn(toon())
        `when`(drafts.draftFromText(anyString(), anyString(), anyArg())).thenReturn(draft())

        val result = service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "유튜브 쇼츠", "틱톡")))

        val outputs = saved().outputs
        assertEquals(result.id, saved().id)
        assertEquals(listOf("인스타툰", "유튜브 쇼츠"), outputs.map { it.channel })
        assertEquals(listOf("toon-1", "draft-1"), outputs.map { it.refId })
        assertTrue(outputs.all { it.status == "성공" && it.body.isNotBlank() })
        verify(llm, never()).chat(anyString(), anyString(), anyBoolean())
    }

    @Test
    fun `한 채널이 실패해도 나머지는 저장하고 실패 사유를 남긴다`() {
        `when`(toons.generate(anyArg())).thenThrow(IllegalStateException("모델 401"))
        llmAnswers("""{"title":"카드 제목","cards":[{"number":1,"headline":"헤드","body":"본문"}]}""")

        service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "카드뉴스")))

        val (toon, cards) = saved().outputs
        assertEquals("실패", toon.status)
        assertEquals("모델 401", toon.error)
        assertEquals("성공", cards.status)
        assertTrue(cards.body.startsWith("1장. 헤드"))
    }

    @Test
    fun `컷 수와 원본 id 를 채널 에이전트에 넘기고 성공하면 키워드를 소진한다`() {
        var toonRequest: CreateInstagramToonRequest? = null
        doAnswer { toonRequest = it.getArgument(0); toon() }.`when`(toons).generate(anyArg())
        `when`(drafts.draftFromText(anyString(), anyString(), anyArg())).thenReturn(draft())

        service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "유튜브 쇼츠"), panelCount = 8, sourceId = "news-1", keywordId = 7))

        assertEquals(8, toonRequest?.panelCount)
        verify(drafts).draftFromText(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("news-1"))
        verify(automation).markKeywordUsed(7)
    }

    @Test
    fun `전부 실패하거나 데모면 키워드를 소진하지 않는다`() {
        `when`(toons.generate(anyArg())).thenThrow(IllegalStateException("모델 401"))
        service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰"), keywordId = 7))
        verify(automation, never()).markKeywordUsed(7)

        llmAnswers("""{"title":"카드 제목","cards":[{"number":1,"headline":"헤드","body":"본문"}]}""")
        DemoContext.set("demo-session")
        try {
            service.create(CreateContentPackageRequest(source = source, channels = listOf("카드뉴스"), keywordId = 7))
        } finally {
            DemoContext.clear()
        }
        verify(automation, never()).markKeywordUsed(7)
    }

    @Test
    fun `재시작 뒤 남은 생성중 채널은 실패로 표시한다`() {
        val service = build(inlineExecutor(runInline = false))
        service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰")))

        service.failOrphans()

        val output = service.list().single().outputs.single()
        assertEquals("실패", output.status)
        assertTrue(output.error!!.contains("재시작"))
    }

    @Test
    fun `블로그는 워커 발행 큐에 검토 대기로 넣는다`() {
        llmAnswers("""{"title":"블로그 제목","content":"본문 전문","tags":["습관"]}""")

        var queued: SaveContentRequest? = null
        doAnswer { queued = it.getArgument(0); null }.`when`(automation).saveContent(anyArg())

        service.create(CreateContentPackageRequest(source = source, channels = listOf("블로그")))

        assertEquals("pending", queued?.status)
        assertEquals("블로그 제목", queued?.title)
        assertEquals(queued?.id, saved().outputs.single().refId)
    }

    @Test
    fun `데모에서는 블로그를 발행 큐에 넣지 않는다`() {
        llmAnswers("""{"title":"블로그 제목","content":"본문 전문","tags":[]}""")
        DemoContext.set("demo-session")
        try {
            service.create(CreateContentPackageRequest(source = source, channels = listOf("블로그")))
            assertNull(saved().outputs.single().refId)
        } finally {
            DemoContext.clear()
        }
        verify(automation, never()).saveContent(anyArg())
    }

    @Test
    fun `원본이 20자 미만이거나 아는 채널이 없으면 거부한다`() {
        assertEquals("원본 콘텐츠를 20자 이상 입력하세요.", assertFailsWith<IllegalArgumentException> {
            service.create(CreateContentPackageRequest(source = "짧은 메모", channels = listOf("블로그")))
        }.message)
        assertEquals("만들 콘텐츠 채널을 하나 이상 선택하세요.", assertFailsWith<IllegalArgumentException> {
            service.create(CreateContentPackageRequest(source = source, channels = listOf("틱톡")))
        }.message)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyArg(): T = ArgumentMatchers.any()

/** runInline=true 면 제출 즉시 같은 스레드에서 돌리고, false 면 아무것도 돌리지 않는다(재시작 전 상태 재현). */
private fun inlineExecutor(runInline: Boolean): ExecutorService = object : AbstractExecutorService() {
    override fun execute(command: Runnable) { if (runInline) command.run() }
    override fun shutdown() {}
    override fun shutdownNow(): List<Runnable> = emptyList()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
