package com.backoffice.dashboard.content

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 예외를 HTTP 상태로 옮기는 규칙만 검증한다.
 * 설정·입력 문제는 400(다시 시도해도 같음), 외부 모델·워커 문제는 502(재시도 가능)로 갈라야
 * 화면이 "다시 시도"와 "설정을 고치세요"를 구분해 안내할 수 있다.
 */
class ContentControllerTest {
    private val briefing = mock(AiNewsBriefingService::class.java)
    private val topicDrafts = mock(TopicDraftService::class.java)
    private val toons = mock(InstagramToonService::class.java)
    private val contentStudio = mock(ContentStudioService::class.java)
    private val news = mock(AiNewsService::class.java)

    private val controller = ContentController(
        instagramToonService = toons,
        toonImageService = mock(ToonImageService::class.java),
        aiNewsService = news,
        aiNewsBriefingService = briefing,
        contentStudioService = contentStudio,
        topicDraftService = topicDrafts,
    )

    private fun status(block: () -> Any?) = assertFailsWith<ResponseStatusException> { block() }.statusCode

    @Test
    fun `브리핑은 설정 문제면 400, 모델 문제면 502 다`() {
        doThrow(IllegalArgumentException("AI 소식을 먼저 수집하세요.")).`when`(briefing).refresh()
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.refreshAiNewsBriefing() })

        doThrow(IllegalStateException("401 응답")).`when`(briefing).refresh()
        assertEquals(HttpStatus.BAD_GATEWAY, status { controller.refreshAiNewsBriefing() })
    }

    @Test
    fun `브리핑의 예상 못 한 예외도 502 로 내리고 예외 종류를 남긴다`() {
        doThrow(RuntimeException()).`when`(briefing).refresh()

        val error = assertFailsWith<ResponseStatusException> { controller.refreshAiNewsBriefing() }

        assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
        // 메시지가 빈 예외는 종류라도 남아야 운영 센터에서 원인을 좁힐 수 있다.
        assertTrue(error.reason!!.contains("RuntimeException"), "실제 사유: ${error.reason}")
    }

    @Test
    fun `주제 대본 초안도 같은 규칙으로 상태를 나눈다`() {
        doThrow(IllegalArgumentException("새 주제가 없습니다.")).`when`(topicDrafts).refresh()
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.refreshTopicDrafts() })

        doThrow(IllegalStateException("모델 호출 실패")).`when`(topicDrafts).refresh()
        assertEquals(HttpStatus.BAD_GATEWAY, status { controller.refreshTopicDrafts() })
    }

    @Test
    fun `없는 초안에 알림을 다시 보내면 400 이다`() {
        doThrow(IllegalArgumentException("초안을 찾을 수 없습니다.")).`when`(topicDrafts).notify("없는-id")

        val error = assertFailsWith<ResponseStatusException> { controller.notifyTopicDraft("없는-id") }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        assertEquals("초안을 찾을 수 없습니다.", error.reason)
    }

    @Test
    fun `인스타툰은 입력 문제면 400, 워커 문제면 502 다`() {
        val request = CreateInstagramToonRequest(episode = "충분히 긴 에피소드 설명")
        doThrow(IllegalArgumentException("컷 수는 4 또는 8만 가능합니다.")).`when`(toons).generate(request)
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.createInstagramToon(request) })

        doThrow(IllegalStateException("대본 생성 시간 초과")).`when`(toons).generate(request)
        assertEquals(HttpStatus.BAD_GATEWAY, status { controller.createInstagramToon(request) })
    }

    @Test
    fun `콘텐츠 패키지 입력 오류는 400 이다`() {
        val request = CreateContentPackageRequest(source = "짧다")
        doThrow(IllegalArgumentException("원본 콘텐츠를 20자 이상 입력하세요.")).`when`(contentStudio).create(request)

        assertEquals(HttpStatus.BAD_REQUEST, status { controller.createContentPackage(request) })
    }

    @Test
    fun `아침 사전 준비는 한 단계가 실패해도 다음 단계를 돌리고 단계별 결과를 돌려준다`() {
        doThrow(IllegalStateException("RSS 연결 실패")).`when`(news).refresh()
        doThrow(IllegalArgumentException("새 주제가 없습니다.")).`when`(topicDrafts).refresh()

        val result = controller.morningPrep()

        assertEquals(listOf("news", "briefing", "topicDraft"), result.keys.toList())
        assertEquals("실패: RSS 연결 실패", result["news"])
        assertEquals("성공", result["briefing"])
        assertEquals("실패: 새 주제가 없습니다.", result["topicDraft"])
        verify(briefing).refresh()
    }
}
