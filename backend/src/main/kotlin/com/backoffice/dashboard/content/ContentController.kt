package com.backoffice.dashboard.content

import com.backoffice.dashboard.LlmClient
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class ContentController(
    private val instagramToonService: InstagramToonService,
    private val toonImageService: ToonImageService,
    private val aiNewsService: AiNewsService,
    private val aiNewsBriefingService: AiNewsBriefingService,
    private val contentStudioService: ContentStudioService,
    private val topicDraftService: TopicDraftService,
) {
    @GetMapping("/instagram-toons")
    fun instagramToons() = instagramToonService.list()

    @GetMapping("/content-packages")
    fun contentPackages() = contentStudioService.list()

    /** 채널을 백그라운드에서 채운다. 잡아 두고 바로 202, 채널별 진행 상태는 목록 조회로 본다. */
    @PostMapping("/content-packages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createContentPackage(@RequestBody request: CreateContentPackageRequest): ContentPackage = try {
        contentStudioService.create(request)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    }

    /**
     * 아침 점검 전에 워커 크론이 한 번 부른다. 소식 수집 → 핵심 3건 요약 → 주제 초안 순서.
     * 한 단계가 실패해도 다음 단계는 돈다. 실패 알림은 각 서비스의 운영 센터 기록이 이미 Slack 으로 보낸다.
     */
    @PostMapping("/worker/morning-prep")
    fun morningPrep(): Map<String, String> = linkedMapOf(
        "news" to step { aiNewsService.refresh() },
        "briefing" to step { aiNewsBriefingService.refresh() },
        "topicDraft" to step { topicDraftService.refresh() },
    )

    private fun step(block: () -> Any?): String = runCatching { block() }.fold({ "성공" }, { "실패: ${LlmClient.reasonOf(it)}" })

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
}
