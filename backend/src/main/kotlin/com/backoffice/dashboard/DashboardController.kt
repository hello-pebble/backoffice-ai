package com.backoffice.dashboard

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api")
class DashboardController(
    private val gmailService: GmailService,
    private val tossService: TossService,
    private val automationService: PythonAutomationService,
    private val operationsService: OperationsService,
    private val instagramToonService: InstagramToonService,
    private val toonImageService: ToonImageService,
    private val aiNewsService: AiNewsService,
    private val aiNewsBriefingService: AiNewsBriefingService,
    private val aiOperationsService: AiOperationsService,
    private val contentStudioService: ContentStudioService,
    private val topicDraftService: TopicDraftService,
    private val authService: AuthService,
    private val slackService: SlackService,
    private val properties: OfficeProperties,
    private val automationRepository: AutomationRepository,
    private val jdbc: JdbcTemplate,
) {
    // 구글·토스를 주인 자격증명으로 부르는 유일한 경로라 데모는 여기서 갈라야 한다.
    // 서비스 안에서 가르면 GmailService 의 60초 성공 캐시를 타고 주인 메일이 샐 수 있다.
    @GetMapping("/dashboard")
    fun dashboard() = if (DemoContext.isDemo()) DEMO_DASHBOARD.copy(generatedAt = OffsetDateTime.now().toString())
    else DashboardResponse(
        generatedAt = OffsetDateTime.now().toString(),
        gmail = gmailService.overview(),
        stocks = tossService.overview(),
    )

    @PostMapping("/automation/{mode}")
    fun runAutomation(@PathVariable mode: String): AutomationResponse {
        if (mode !in setOf("keyword", "content", "posting", "all")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 자동화 모드입니다.")
        }
        return automationService.run(mode).also { operationsService.recordRun(mode, it) }
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, Any>> = try {
        jdbc.queryForObject("select 1", Int::class.java)
        ResponseEntity.ok(mapOf<String, Any>("ok" to true, "database" to "up"))
    } catch (error: Exception) {
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf<String, Any>("ok" to false, "database" to "down"))
    }

    @GetMapping("/operations")
    fun operations() = operationsService.snapshot()

    @PostMapping("/tasks")
    fun createTask(@RequestBody request: CreateTaskRequest) = operationsService.createTask(request)

    @PatchMapping("/tasks/{id}/status")
    fun updateTask(@PathVariable id: Long, @RequestBody request: ChangeStatusRequest) = operationsService.changeTask(id, request)

    @DeleteMapping("/tasks/{id}")
    fun deleteTask(@PathVariable id: Long) = operationsService.deleteTask(id)

    @PatchMapping("/approvals/{id}/status")
    fun updateApproval(@PathVariable id: String, @RequestBody request: ChangeStatusRequest) = operationsService.changeApproval(id, request)

    @GetMapping("/instagram-toons")
    fun instagramToons() = instagramToonService.list()

    @GetMapping("/content-packages")
    fun contentPackages() = contentStudioService.list()

    @PostMapping("/content-packages")
    fun createContentPackage(@RequestBody request: CreateContentPackageRequest): ContentPackage = try {
        contentStudioService.create(request)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    /** 이미지는 1~3분 걸려 기다리지 않는다. 잡아 두고 바로 202, 진행 상태는 목록 조회로 본다. */
    @PostMapping("/instagram-toons/{id}/images")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createToonImages(@PathVariable id: String): List<ToonImageStatus> = try {
        toonImageService.enqueue(id)
    } catch (error: IllegalArgumentException) {
        // 예산 초과도 여기로 온다. 화면은 detail 문구를 그대로 보여 준다.
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    /** 바이트는 목록 JSON 에 싣지 않는다. 브라우저가 img 태그로 따로 가져간다. */
    @GetMapping("/toon-images/{id}")
    fun toonImage(@PathVariable id: Long): ResponseEntity<ByteArray> =
        toonImageService.bytes(id)?.let { (mimeType, bytes) ->
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(bytes)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다.")

    @PostMapping("/instagram-toons")
    fun createInstagramToon(@RequestBody request: CreateInstagramToonRequest): InstagramToon = try {
        instagramToonService.generate(request)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (error: IllegalStateException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, error.message)
    }

    @GetMapping("/ai-news")
    fun aiNews() = aiNewsService.list()

    @GetMapping("/ai-operations")
    fun aiOperations() = aiOperationsService.overview()

    @PostMapping("/ai-news/refresh")
    fun refreshAiNews() = aiNewsService.refresh()

    @PatchMapping("/ai-news/{id}/read")
    fun readAiNews(@PathVariable id: String) = aiNewsService.markRead(id)

    @GetMapping("/ai-news/briefing")
    fun aiNewsBriefing(): ResponseEntity<AiNewsBriefing> = aiNewsBriefingService.get()?.let { ResponseEntity.ok(it) }
        ?: ResponseEntity.noContent().build()

    @PostMapping("/ai-news/briefing/refresh")
    fun refreshAiNewsBriefing(): AiNewsBriefing = try {
        aiNewsBriefingService.refresh()
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (error: IllegalStateException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, error.message)
    } catch (error: Exception) {
        // 서비스가 원인과 대상 주소를 메시지에 담아 IllegalStateException 으로 올린다.
        // 여기까지 오는 건 그 밖의 경우뿐이라 예외 종류라도 남긴다.
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 브리핑 처리 중 오류가 발생했습니다: ${LlmClient.reasonOf(error)}")
    }

    @GetMapping("/topic-drafts")
    fun topicDrafts() = topicDraftService.list()

    @PostMapping("/topic-drafts/refresh")
    fun refreshTopicDrafts(): TopicDraft = try {
        topicDraftService.refresh()
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (error: IllegalStateException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, error.message)
    } catch (error: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "주제 대본 초안 처리 중 오류가 발생했습니다: ${LlmClient.reasonOf(error)}")
    }

    // 알림 재시도. Slack 이 실패해도 초안은 이미 저장돼 있으므로 여기서만 다시 보낸다.
    @PostMapping("/topic-drafts/{id}/notify")
    fun notifyTopicDraft(@PathVariable id: String): TopicDraft = try {
        topicDraftService.notify(id)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    // 워커(Python)가 DB 에 직접 쓰지 않고 결과만 넘긴다. 스키마를 아는 곳은 백엔드 하나다.
    @PostMapping("/worker/keywords")
    fun saveWorkerKeyword(@RequestBody request: SaveKeywordRequest): Map<String, Long> = try {
        mapOf("id" to automationRepository.saveKeyword(request))
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    @GetMapping("/worker/keywords/unused")
    fun unusedWorkerKeywords(limit: Int?): List<AutomationKeyword> =
        automationRepository.unusedKeywords((limit ?: 10).coerceIn(1, 100))

    @PostMapping("/worker/contents")
    fun saveWorkerContent(@RequestBody request: SaveContentRequest): Map<String, Boolean> = try {
        automationRepository.saveContent(request)
        mapOf("ok" to true)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    @PostMapping("/worker/posting-records")
    fun saveWorkerPostingRecord(@RequestBody request: SavePostingRecordRequest): Map<String, Boolean> = try {
        automationRepository.savePostingRecord(request)
        mapOf("ok" to true)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    @GetMapping("/auth/login")
    // 브라우저 내비게이션에는 쿠키·헤더를 붙일 수 없어 302 대신 주소를 돌려주고 화면에서 이동한다.
    fun login(): Map<String, String> = try {
        mapOf("url" to authService.authorizationUrl())
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @GetMapping("/auth/callback")
    fun authCallback(code: String?, state: String?): ResponseEntity<String> {
        val result = if (code.isNullOrBlank() || state.isNullOrBlank()) LoginResult(null, "로그인 정보가 올바르지 않습니다.")
        else authService.completeLogin(code, state)
        val token = result.token
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Content-Type", "text/html; charset=utf-8")
                .body(page(result.error ?: "로그인하지 못했습니다."))
        // Max-Age 를 주지 않는 브라우저 세션 쿠키. 브라우저를 완전히 닫으면 로그아웃된다.
        // 안 닫고 계속 쓰는 경우는 서버 세션 만료(office.auth.session-hours)가 상한이다.
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, maxAgeSeconds = null).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("1", maxAgeSeconds = null).toString())
            .header(HttpHeaders.LOCATION, properties.auth.successRedirect)
            .body("")
    }

    @GetMapping("/auth/me")
    fun me(request: HttpServletRequest): Map<String, Any> {
        val email = authService.emailOf(SessionAuthFilter.sessionToken(request))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        return mapOf("email" to email, "demo" to (email == DemoContext.EMAIL))
    }

    /** 로그인 없이 둘러보기. 예약 이메일로 세션을 만들어 화면이 로그인 상태로 동작하게 한다. */
    @PostMapping("/auth/demo")
    fun demoLogin(): ResponseEntity<Map<String, Boolean>> = try {
        val token = authService.startDemoSession()
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, maxAgeSeconds = null).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("1", maxAgeSeconds = null).toString())
            .body(mapOf("ok" to true))
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Map<String, Boolean>> {
        authService.logout(SessionAuthFilter.sessionToken(request))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString())
            .header(HttpHeaders.SET_COOKIE, hintCookie("", 0).toString())
            .body(mapOf("ok" to true))
    }

    private fun sessionCookie(value: String, maxAgeSeconds: Long?) =
        cookie(SessionAuthFilter.COOKIE, value, maxAgeSeconds, httpOnly = true)

    /**
     * 세션 쿠키는 HttpOnly 라 화면이 읽을 수 없다. 그래서 로그인 여부를 알려면 /api/auth/me 왕복을
     * 기다려야 하고, 그동안 새로고침마다 로그인 카드가 깜빡였다.
     * 값이 없는 힌트 쿠키를 하나 더 줘서 화면이 첫 줄에서 바로 판단하게 한다.
     * 이 쿠키는 인증에 쓰이지 않는다. 위조해도 서버는 세션 쿠키만 본다.
     */
    private fun hintCookie(value: String, maxAgeSeconds: Long?) =
        cookie(SessionAuthFilter.HINT_COOKIE, value, maxAgeSeconds, httpOnly = false)

    /** maxAgeSeconds 가 null 이면 디스크에 남지 않는 브라우저 세션 쿠키가 된다. 로그아웃은 0 으로 지운다. */
    private fun cookie(name: String, value: String, maxAgeSeconds: Long?, httpOnly: Boolean): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(httpOnly).path("/").apply { maxAgeSeconds?.let { maxAge(it) } }
            .secure(properties.auth.cookieSecure).sameSite(properties.auth.cookieSameSite).build()

    private fun page(message: String) = "<!doctype html><html lang=\"ko\"><body><p>$message</p></body></html>"

    @GetMapping("/slack/status")
    fun slackStatus() = slackService.status()

    @GetMapping("/slack/connect")
    fun connectSlack(): Map<String, String> = try {
        mapOf("url" to slackService.installUrl())
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @GetMapping("/slack/callback")
    fun slackCallback(code: String?, state: String?): ResponseEntity<String> {
        val installed = !code.isNullOrBlank() && !state.isNullOrBlank() && slackService.completeInstall(code, state)
        val message = if (installed) "Slack 연결이 완료되었습니다. 이 창을 닫고 대시보드에서 채널을 고르세요." else "Slack 연결을 완료하지 못했습니다. 다시 시도하세요."
        return ResponseEntity.status(if (installed) HttpStatus.OK else HttpStatus.BAD_REQUEST)
            .header("Content-Type", "text/html; charset=utf-8").body(page(message))
    }

    @GetMapping("/slack/channels")
    fun slackChannels(): List<SlackChannel> = try {
        slackService.channels()
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    } catch (error: IllegalStateException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, error.message)
    }

    @PostMapping("/slack/channel")
    fun selectSlackChannel(@RequestBody request: SelectSlackChannelRequest): SlackStatus = try {
        slackService.selectChannel(request.channelId)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    companion object {
        /**
         * 데모의 메일·종목 칸. 코드에 박아 둔다 — 주인 계정을 부르지 않고, 가릴 것도 없다.
         * 발신자는 전부 example.com 이고 시세는 고정값이다(토스 자격증명을 쓰지 않는다).
         */
        private val DEMO_DASHBOARD = DashboardResponse(
            generatedAt = "",
            gmail = GmailOverview(
                connected = true,
                unread = 3,
                messages = listOf(
                    MailItem("collab@example.com", "9월 브랜디드 콘텐츠 협업 문의", "Fri, 5 Sep 2026 09:12:00 +0900"),
                    MailItem("news@example.com", "이번 주 AI 도구 업데이트 모음", "Fri, 5 Sep 2026 08:40:00 +0900"),
                    MailItem("billing@example.com", "8월 이용 내역 안내", "Thu, 4 Sep 2026 18:02:00 +0900"),
                ),
            ),
            stocks = StockOverview(
                connected = true,
                items = listOf(
                    StockItem("005930", "삼성전자", "257000", "KRW", null),
                    StockItem("000660", "SK하이닉스", "1662000", "KRW", null),
                    StockItem("373220", "LG에너지솔루션", "360500", "KRW", null),
                ),
            ),
        )
    }
}

data class SelectSlackChannelRequest(val channelId: String = "")
data class DashboardResponse(val generatedAt: String, val gmail: GmailOverview, val stocks: StockOverview)
data class GmailOverview(val connected: Boolean, val message: String? = null, val unread: Int? = null, val messages: List<MailItem> = emptyList(), val more: Boolean = false)
data class MailItem(val from: String, val subject: String, val date: String)
data class StockOverview(val connected: Boolean, val message: String? = null, val items: List<StockItem> = emptyList())
data class StockItem(val symbol: String, val name: String, val price: String, val currency: String, val timestamp: String?)
data class AutomationResponse(val success: Boolean, val exitCode: Int?, val output: String)
