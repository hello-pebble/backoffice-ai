package com.backoffice.dashboard

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 예외를 HTTP 상태로 옮기는 규칙만 검증한다.
 * 설정·입력 문제는 400(다시 시도해도 같음), 외부 모델·워커 문제는 502(재시도 가능)로 갈라야
 * 화면이 "다시 시도"와 "설정을 고치세요"를 구분해 안내할 수 있다.
 */
class DashboardControllerTest {
    private val briefing = mock(AiNewsBriefingService::class.java)
    private val topicDrafts = mock(TopicDraftService::class.java)
    private val toons = mock(InstagramToonService::class.java)
    private val contentStudio = mock(ContentStudioService::class.java)
    private val gmail = mock(GmailService::class.java)
    private val slack = mock(SlackService::class.java)
    private val auth = mock(AuthService::class.java)
    private val jdbc = mock(JdbcTemplate::class.java)

    private val controller = DashboardController(
        gmailService = gmail,
        tossService = mock(TossService::class.java),
        automationService = mock(PythonAutomationService::class.java),
        operationsService = mock(OperationsService::class.java),
        instagramToonService = toons, toonImageService = mock(ToonImageService::class.java),
        aiNewsService = mock(AiNewsService::class.java),
        aiNewsBriefingService = briefing,
        aiOperationsService = mock(AiOperationsService::class.java),
        contentStudioService = contentStudio,
        topicDraftService = topicDrafts,
        authService = auth,
        slackService = slack,
        properties = OfficeProperties(),
        automationRepository = mock(AutomationRepository::class.java),
        jdbc = jdbc,
    )

    private fun status(block: () -> Any?) = assertFailsWith<ResponseStatusException> { block() }.statusCode

    @AfterEach
    fun clearDemoContext() = DemoContext.clear()

    @Test
    fun `데모 대시보드는 주인 자격증명으로 구글·토스를 부르지 않는다`() {
        val toss = mock(TossService::class.java)
        val demoController = DashboardController(
            gmailService = gmail, tossService = toss,
            automationService = mock(PythonAutomationService::class.java),
            operationsService = mock(OperationsService::class.java),
            instagramToonService = toons, toonImageService = mock(ToonImageService::class.java), aiNewsService = mock(AiNewsService::class.java),
            aiNewsBriefingService = briefing, aiOperationsService = mock(AiOperationsService::class.java),
            contentStudioService = contentStudio, topicDraftService = topicDrafts,
            authService = auth, slackService = slack, properties = OfficeProperties(),
            automationRepository = mock(AutomationRepository::class.java), jdbc = jdbc,
        )
        DemoContext.set("세션-해시")

        val response = demoController.dashboard()

        verify(gmail, never()).overview()
        verify(toss, never()).overview()
        // 발신자가 실제 주소면 주인 메일함이 샌 것이다.
        assertTrue(response.gmail.messages.all { it.from.endsWith("@example.com") }, "데모 메일은 예시 주소여야 한다")
        assertTrue(response.generatedAt.isNotBlank())
    }

    @Test
    fun `허용하지 않은 자동화 모드는 400 이다`() {
        assertEquals(HttpStatus.BAD_REQUEST, status { controller.runAutomation("드롭테이블") })
    }

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
    fun `DB 가 죽으면 health 는 503 이다`() {
        `when`(jdbc.queryForObject("select 1", Int::class.java)).thenThrow(RuntimeException("connection refused"))

        val response = controller.health()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals(false, response.body?.get("ok"))
        assertEquals("down", response.body?.get("database"))
    }

    @Test
    fun `Slack 자격증명이 없으면 연결 주소 요청은 409 다`() {
        doThrow(IllegalArgumentException("Slack 앱 자격증명이 설정되지 않았습니다.")).`when`(slack).installUrl()

        assertEquals(HttpStatus.CONFLICT, status { controller.connectSlack() })
    }

    @Test
    fun `Slack 채널 조회는 미연결이면 409, Slack 오류면 502 다`() {
        doThrow(IllegalArgumentException("Slack 이 아직 연결되지 않았습니다.")).`when`(slack).channels()
        assertEquals(HttpStatus.CONFLICT, status { controller.slackChannels() })

        doThrow(IllegalStateException("Slack 채널 목록을 가져오지 못했습니다: invalid_auth")).`when`(slack).channels()
        assertEquals(HttpStatus.BAD_GATEWAY, status { controller.slackChannels() })
    }

    @Test
    fun `로그인 쿠키는 Max-Age 없는 브라우저 세션 쿠키다`() {
        `when`(auth.completeLogin("코드", "상태")).thenReturn(LoginResult("session-token", null, "owner@example.com"))

        val cookies = controller.authCallback("코드", "상태").headers[org.springframework.http.HttpHeaders.SET_COOKIE].orEmpty()

        // Max-Age 를 주면 쿠키가 디스크에 남아 브라우저·컴퓨터를 껐다 켜도 로그인이 유지된다.
        assertEquals(2, cookies.size, "실제 쿠키: $cookies")
        assertTrue(cookies.none { it.contains("Max-Age") }, "브라우저를 닫아도 남는 쿠키가 있다: $cookies")
    }

    @Test
    fun `로그아웃하면 세션 쿠키와 표시용 쿠키를 함께 지운다`() {
        val cookies = controller.logout(MockHttpServletRequest()).headers[org.springframework.http.HttpHeaders.SET_COOKIE].orEmpty()

        // 표시용 쿠키가 남으면 화면이 로그인된 줄 알고 대시보드를 그린 뒤 401 로 튕긴다.
        assertEquals(2, cookies.size, "실제 쿠키: $cookies")
        assertTrue(cookies.any { it.startsWith("${SessionAuthFilter.COOKIE}=;") }, "세션 쿠키 삭제 없음: $cookies")
        assertTrue(cookies.any { it.startsWith("${SessionAuthFilter.HINT_COOKIE}=;") }, "표시용 쿠키 삭제 없음: $cookies")
        assertTrue(cookies.all { it.contains("Max-Age=0") }, "만료 설정 없음: $cookies")
    }

    @Test
    fun `표시용 쿠키는 화면이 읽어야 하므로 HttpOnly 가 아니다`() {
        val cookies = controller.logout(MockHttpServletRequest()).headers[org.springframework.http.HttpHeaders.SET_COOKIE].orEmpty()

        assertTrue(cookies.first { it.startsWith(SessionAuthFilter.COOKIE + "=") }.contains("HttpOnly"))
        assertEquals(false, cookies.first { it.startsWith(SessionAuthFilter.HINT_COOKIE + "=") }.contains("HttpOnly"))
    }
}
