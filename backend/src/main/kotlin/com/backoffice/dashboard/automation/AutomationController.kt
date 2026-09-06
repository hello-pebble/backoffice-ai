package com.backoffice.dashboard.automation

import com.backoffice.dashboard.operations.OperationsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class AutomationController(
    private val automationService: PythonAutomationService,
    private val operationsService: OperationsService,
    private val slackService: SlackService,
) {
    @PostMapping("/automation/{mode}")
    fun runAutomation(@PathVariable mode: String): AutomationResponse {
        if (mode !in setOf("keyword", "content", "posting", "all")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 자동화 모드입니다.")
        }
        return automationService.run(mode).also { operationsService.recordRun(mode, it) }
    }

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
            .header("Content-Type", "text/html; charset=utf-8")
            .body("<!doctype html><html lang=\"ko\"><body><p>$message</p></body></html>")
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
}

data class SelectSlackChannelRequest(val channelId: String = "")
data class AutomationResponse(val success: Boolean, val exitCode: Int?, val output: String)
