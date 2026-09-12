package com.backoffice.dashboard.operations

import com.backoffice.dashboard.AiOperationsService
import com.backoffice.dashboard.DemoContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api")
class OperationsController(
    private val gmailService: GmailService,
    private val tossService: TossService,
    private val operationsService: OperationsService,
    private val aiOperationsService: AiOperationsService,
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

    /** 필터·페이지는 전부 서버가 한다. range 는 today · 7d · YYYY-MM, 비우면 보관 기간 전체. */
    @GetMapping("/ai-operations")
    fun aiOperations(range: String?, agent: String?, model: String?, page: Int?, size: Int?) = aiOperationsService.overview(
        range = range ?: "today",
        agent = agent?.ifBlank { null },
        model = model?.ifBlank { null },
        page = (page ?: 0).coerceAtLeast(0),
        size = (size ?: AiOperationsService.PAGE_SIZE).coerceIn(1, 100),
    )

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

    @GetMapping("/worker/contents")
    fun workerContents(status: String?, limit: Int?): List<AutomationContent> =
        automationRepository.contentsByStatus(status ?: "approved", (limit ?: 10).coerceIn(1, 100))

    /** 발행 완료 표시. 본문을 다시 보내지 않아도 되도록 상태만 받는다. */
    @PatchMapping("/worker/contents/{id}")
    fun updateWorkerContent(@PathVariable id: String, @RequestBody request: UpdateContentStatusRequest): Map<String, Boolean> = try {
        require(request.status.isNotBlank()) { "status 가 비어 있습니다." }
        automationRepository.updateContentStatus(id, request.status, request.postedDate)
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

data class DashboardResponse(val generatedAt: String, val gmail: GmailOverview, val stocks: StockOverview)
data class GmailOverview(val connected: Boolean, val message: String? = null, val unread: Int? = null, val messages: List<MailItem> = emptyList(), val more: Boolean = false)
data class MailItem(val from: String, val subject: String, val date: String)
data class StockOverview(val connected: Boolean, val message: String? = null, val items: List<StockItem> = emptyList())
data class StockItem(val symbol: String, val name: String, val price: String, val currency: String, val timestamp: String?)
