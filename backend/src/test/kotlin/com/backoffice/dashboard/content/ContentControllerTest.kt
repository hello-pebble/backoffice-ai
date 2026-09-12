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
    fun `콘텐츠 패키지 입력 오류는 400 이다`() {
        val request = CreateContentPackageRequest(source = "짧다")
        doThrow(IllegalArgumentException("원본 콘텐츠를 20자 이상 입력하세요.")).`when`(contentStudio).create(request)

        assertEquals(HttpStatus.BAD_REQUEST, status { controller.createContentPackage(request) })
    }

    @Test
    fun `검토 상태가 잘못되면 400 이다`() {
        doThrow(IllegalArgumentException("검토 상태는")).`when`(contentStudio).review("p1", "블로그", "MAYBE")
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.reviewContentOutput("p1", "블로그", ReviewRequest("MAYBE")) })
    }

    @Test
    fun `주제 후보가 없으면 204 다`() {
        org.mockito.Mockito.`when`(topicDrafts.nextCandidate()).thenReturn(null)
        assertEquals(HttpStatus.NO_CONTENT, controller.nextTopicCandidate().statusCode)
    }

    @Test
    fun `아침 사전 준비는 한 단계가 실패해도 다음 단계를 돌리고 단계별 결과를 돌려준다`() {
        doThrow(IllegalStateException("RSS 연결 실패")).`when`(news).refresh()
        org.mockito.Mockito.`when`(topicDrafts.nextCandidate()).thenReturn(null)

        val result = controller.morningPrep()

        assertEquals(listOf("news", "briefing", "package"), result.keys.toList())
        assertEquals("실패: RSS 연결 실패", result["news"])
        assertEquals("성공", result["briefing"])
        assertEquals("실패: 초안으로 만들 새 주제가 없습니다.", result["package"])
        verify(briefing).refresh()
    }

    @Test
    fun `아침 사전 준비는 우선순위 주제로 쇼츠 패키지를 만든다`() {
        val candidate = DraftSource.fromText("아침 주제", "아침에 커피를 마시며 할 일을 정리하는 습관 이야기", "news-1")
        org.mockito.Mockito.`when`(topicDrafts.nextCandidate()).thenReturn(candidate)
        var request: CreateContentPackageRequest? = null
        org.mockito.Mockito.doAnswer { request = it.getArgument(0); ContentPackage("p1", "t", "s", "톤", "대상", "2026-09-12T06:30:00+09:00", emptyList()) }
            .`when`(contentStudio).create(anyArg())

        assertEquals("성공", controller.morningPrep()["package"])
        assertEquals(listOf("유튜브 쇼츠"), request?.channels)
        assertEquals("news-1", request?.sourceId)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any()
