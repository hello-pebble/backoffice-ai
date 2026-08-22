package com.backoffice.dashboard

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    fun health() = mapOf("ok" to true)

    @GetMapping("/operations")
    fun operations() = operationsService.snapshot()

    @PostMapping("/tasks")
    fun createTask(@RequestBody request: CreateTaskRequest) = operationsService.createTask(request)

    @PatchMapping("/tasks/{id}/status")
    fun updateTask(@PathVariable id: String, @RequestBody request: ChangeStatusRequest) = operationsService.changeTask(id, request)

    @DeleteMapping("/tasks/{id}")
    fun deleteTask(@PathVariable id: String) = operationsService.deleteTask(id)

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
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 브리핑 처리 중 오류가 발생했습니다. AI 운영 센터에서 실행 상태를 확인하세요.")
    }

    @GetMapping("/gmail/connect")
    fun connectGmail(): ResponseEntity<Void> = try {
        ResponseEntity.status(HttpStatus.FOUND).header("Location", gmailService.authorizationUrl()).build()
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

data class DashboardResponse(val generatedAt: String, val gmail: GmailOverview, val stocks: StockOverview)
data class GmailOverview(val connected: Boolean, val message: String? = null, val unread: Int? = null, val messages: List<MailItem> = emptyList())
data class MailItem(val from: String, val subject: String, val date: String)
data class StockOverview(val connected: Boolean, val message: String? = null, val items: List<StockItem> = emptyList())
data class StockItem(val symbol: String, val name: String, val price: String, val currency: String, val timestamp: String?)
data class AutomationResponse(val success: Boolean, val exitCode: Int?, val output: String)
