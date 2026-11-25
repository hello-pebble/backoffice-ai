package com.backoffice.dashboard.content

import com.backoffice.dashboard.*
import com.backoffice.dashboard.operations.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import kotlin.test.assertEquals
// import kotlin.test.assertNotNull  // 위 주석 처리한 단언에서만 쓰던 import
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicDraftServiceTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-08-26T09:00:00Z")
    private val documents = FakeDocumentStore()

    private fun news(id: String, category: String, hoursAgo: Long) = AiNewsItem(
        id = id,
        source = "OpenAI",
        title = "제목 $id",
        url = "https://example.com/$id",
        summary = "요약 $id",
        publishedAt = now.minusHours(hoursAgo).toString(),
        category = category,
        read = false,
        collectedAt = now.toString(),
    )

    private fun service(news: AiNewsService = mock(AiNewsService::class.java), automation: AutomationRepository = mock(AutomationRepository::class.java)) =
        TopicDraftService(news, automation, ObjectMapper(), mock(AiOperationsService::class.java), documents, LlmClient(OfficeProperties(), ObjectMapper()))

    @Test
    fun `카테고리 가중치와 최신성으로 우선순위를 매긴다`() {
        // 같은 시각이면 카테고리 가중치가 순서를 결정한다.
        val model = TopicDraftService.priorityScore(news("a", "모델", 0), now)
        val etc = TopicDraftService.priorityScore(news("b", "업계 소식", 0), now)
        assertTrue(model > etc, "모델 가중치 5 가 기타 1 보다 앞서야 한다")
        // 방금 올라온 글은 최신성 만점 5 를 더 받는다.
        assertEquals(10.0, model, 0.001)
        // 48시간이 지나면 최신성 점수가 1/e 로 줄어든다.
        assertEquals(5.0 + 5.0 * Math.exp(-1.0), TopicDraftService.priorityScore(news("c", "모델", 48), now), 0.001)
        // 오래된 모델 소식보다 방금 올라온 에이전트 소식이 앞선다.
        assertTrue(TopicDraftService.priorityScore(news("d", "에이전트", 0), now) > TopicDraftService.priorityScore(news("c", "모델", 200), now))
    }

    @Test
    fun `이미 초안으로 만든 주제는 후보에서 제외한다`() {
        val items = listOf(news("a", "모델", 0), news("b", "에이전트", 0))
        assertEquals("a", TopicDraftService.selectCandidate(items, emptySet(), now)?.id)
        assertEquals("b", TopicDraftService.selectCandidate(items, setOf("a"), now)?.id)
        assertNull(TopicDraftService.selectCandidate(items, setOf("a", "b"), now), "남은 후보가 없으면 null")
    }

    @Test
    fun `원본 텍스트 후보는 콘텐츠 스튜디오 출처로 저장된다`() {
        val service = service()
        val script = TopicScript("영상 제목", "3초 훅", "대본 전문", listOf("#AI"))

        val draft = service.persist(DraftSource.fromText("제목", "원본 본문입니다."), script, "gpt-test", now)

        assertEquals("콘텐츠 스튜디오", draft.source)
        assertTrue(draft.sourceId.startsWith("studio-"))
        assertEquals("REVIEW_PENDING", draft.reviewStatus)
        assertEquals(draft.id, documents.readList("topic-drafts", TopicDraft::class.java).single().id)
        // 소식에서 가져온 원본은 그 소식 id 를 남겨 nextCandidate 가 같은 주제를 다시 고르지 않는다.
        assertEquals("news-1", DraftSource.fromText("제목", "원본", "news-1").sourceId)
        // Slack 은 패키지가 보낸다. 초안은 보내지 않는다.
        assertEquals("SKIPPED", draft.slackStatus)
    }

    @Test
    fun `주제 가져오기는 소식·키워드 중 점수가 높은 쪽을 주되 키워드를 소진하지 않는다`() {
        val news = mock(AiNewsService::class.java)
        val automation = mock(AutomationRepository::class.java)
        `when`(news.refresh()).thenReturn(listOf(news("a", "연구·안전", 40)))
        `when`(automation.unusedKeywords(1)).thenReturn(listOf(AutomationKeyword(7, "AI 에이전트", 3000, "에이전트", null, false, 5)))
        val service = service(news, automation)

        val candidate = service.nextCandidate()!!

        assertEquals("keyword-7", candidate.sourceId)
        assertEquals(7L, TopicCandidate.of(candidate).keywordId)
        verify(automation, never()).markKeywordUsed(7)
    }
}
