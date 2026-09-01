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
    private val aiNewsService: AiNewsService,
    private val aiNewsBriefingService: AiNewsBriefingService,
    private val aiOperationsService: AiOperationsService,
    private val contentStudioService: ContentStudioService,
    private val topicDraftService: TopicDraftService,
    private val authService: AuthService,
    private val slackService: SlackService,
    private val properties: OfficeProperties,
    private val jdbc: JdbcTemplate,
) {
    @GetMapping("/dashboard")
    fun dashboard() = DashboardResponse(
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
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, properties.auth.sessionHours * 3600).toString())
            .header(HttpHeaders.LOCATION, properties.auth.successRedirect)
            .body("")
    }

    @GetMapping("/auth/me")
    fun me(request: HttpServletRequest): Map<String, Any> {
        val email = authService.emailOf(SessionAuthFilter.sessionToken(request))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        return mapOf("email" to email)
    }

    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Map<String, Boolean>> {
        authService.logout(SessionAuthFilter.sessionToken(request))
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString()).body(mapOf("ok" to true))
    }

    private fun sessionCookie(value: String, maxAgeSeconds: Long): ResponseCookie =
        ResponseCookie.from(SessionAuthFilter.COOKIE, value)
            .httpOnly(true).path("/").maxAge(maxAgeSeconds)
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

    @GetMapping("/gmail/connect")
    // 브라우저 내비게이션은 X-API-Key 헤더를 붙일 수 없다. 302 대신 URL을 돌려주고
    // 화면에서 이동시켜, 인증 필터에 예외 경로를 뚫지 않는다.
    fun connectGmail(): Map<String, String> = try {
        mapOf("url" to gmailService.authorizationUrl())
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    @GetMapping("/gmail/callback")
    fun gmailCallback(code: String?, state: String?): ResponseEntity<String> {
        val authorized = !code.isNullOrBlank() && !state.isNullOrBlank() && gmailService.completeAuthorization(code, state)
        val message = if (authorized) "Gmail 연결이 완료되었습니다. 이 창을 닫고 대시보드를 새로고침하세요." else "Gmail 연결을 완료하지 못했습니다. 다시 시도하세요."
        return ResponseEntity.status(if (authorized) HttpStatus.OK else HttpStatus.BAD_REQUEST).header("Content-Type", "text/html; charset=utf-8")
            .body("<!doctype html><html lang=\"ko\"><body><p>$message</p></body></html>")
    }
}

data class SelectSlackChannelRequest(val channelId: String = "")
data class DashboardResponse(val generatedAt: String, val gmail: GmailOverview, val stocks: StockOverview)
data class GmailOverview(val connected: Boolean, val message: String? = null, val unread: Int? = null, val messages: List<MailItem> = emptyList(), val more: Boolean = false)
data class MailItem(val from: String, val subject: String, val date: String)
data class StockOverview(val connected: Boolean, val message: String? = null, val items: List<StockItem> = emptyList())
data class StockItem(val symbol: String, val name: String, val price: String, val currency: String, val timestamp: String?)
data class AutomationResponse(val success: Boolean, val exitCode: Int?, val output: String)
