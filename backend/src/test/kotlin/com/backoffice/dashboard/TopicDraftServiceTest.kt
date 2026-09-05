package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
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

    // Slack 은 앱 설치 전 상태 = 알림 미설정. 초안 저장은 그래도 성공해야 한다.
    private fun service(): TopicDraftService {
        val properties = OfficeProperties()
        return TopicDraftService(
            properties = properties,
            aiNewsService = mock(AiNewsService::class.java),
            automationRepository = mock(AutomationRepository::class.java),
            objectMapper = ObjectMapper(),
            aiOperationsService = mock(AiOperationsService::class.java),
            documents = documents,
            llm = LlmClient(properties, ObjectMapper()),
            slack = SlackService(properties, ObjectMapper(), documents),
        )
    }

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
    fun `Slack 이 연결되지 않아도 초안은 검토 대기 상태로 저장된다`() {
        val service = service()
        val script = TopicScript("영상 제목", "3초 훅", "대본 전문", listOf("#AI"))

        val draft = service.persist(DraftSource.fromNews(news("a", "모델", 1), now), script, "llama3.2:1b", now)

        assertEquals("REVIEW_PENDING", draft.reviewStatus)
        assertEquals("NOT_CONFIGURED", draft.slackStatus)
        assertNull(draft.slackError)
        assertTrue(draft.reviewUrl.endsWith("/#topic-draft-${draft.id}"))
        // 저장까지 끝나야 알림 재시도 API 가 초안을 찾을 수 있다.
        val saved = service.list()
        assertEquals(1, saved.size)
        assertEquals(draft.id, saved.first().id)
        // 사용되지 않는 검사라 주석 처리한다. script 는 non-null String 이라 이 단언은 실패할 수 없다.
        // 대본이 비었는지 보려면 isNotBlank 를 봐야 하는데, 그건 persist 가 아니라 parseScript 의 책임이다.
        // assertNotNull(saved.first().script)
    }
}
