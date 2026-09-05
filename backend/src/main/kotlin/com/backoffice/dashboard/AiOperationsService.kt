package com.backoffice.dashboard

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AiOperationsService(
    private val documents: JsonDocumentStore,
    private val slack: SlackService,
    private val properties: OfficeProperties,
) {
    private val log = LoggerFactory.getLogger(AiOperationsService::class.java)

    @Synchronized
    fun record(
        agent: String,
        provider: String,
        model: String,
        tools: List<String>,
        durationMs: Long,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        estimatedCostUsd: Double = 0.0,
        resultPreview: String = "",
        status: String = "성공",
        error: String? = null,
    ) {
        val item = AiOperationRun(
            id = UUID.randomUUID().toString(),
            executedAt = OffsetDateTime.now().toString(),
            agent = agent,
            provider = provider,
            model = LlmClient.canonicalModel(model),
            status = status,
            durationMs = durationMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            tools = tools,
            resultPreview = resultPreview.take(240),
            error = error?.take(240),
        )
        save((listOf(item) + load()).take(MAX_RUNS))
        if (status != "성공") notifyFailure(item)
    }

    /**
     * 실패는 모든 에이전트가 이 함수를 지나므로 여기 한 곳에서 알린다.
     * 성공은 알리지 않는다. 화면에서 직접 누른 결과는 그 자리에서 보이고,
     * 검토가 필요한 결과물(주제 대본 초안)은 만든 쪽이 따로 알린다.
     *
     * 알림 실패가 기록을 막으면 안 된다. 기록이 남아야 운영 센터에서라도 볼 수 있다.
     */
    private fun notifyFailure(run: AiOperationRun) {
        val link = "${properties.slack.reviewBaseUrl.trim().trimEnd('/')}/#ai-operations"
        val reason = run.error ?: "사유가 기록되지 않았습니다."
        val (status, error) = runCatching {
            slack.notify(
            """
            자동화 실패: ${run.agent}
            ${run.provider} · ${run.model}
            사유: $reason
            운영 센터: $link
            """.trimIndent()
            )
        }.getOrElse {
            // 여기서 예외가 새면 원래 실패 사유가 Slack 오류로 덮인다. 기록은 이미 남았다.
            "FAILED" to LlmClient.reasonOf(it)
        }
        if (status == "FAILED") log.warn("실패 알림을 보내지 못했습니다: {}", error)
    }

    @Synchronized
    fun overview(): AiOperationsOverview {
        val items = load().sortedByDescending { it.executedAt }
        val today = LocalDate.now().toString()
        val todayItems = items.filter { it.executedAt.startsWith(today) }
        return AiOperationsOverview(
            totalRuns = todayItems.size,
            totalTokens = todayItems.sumOf { it.inputTokens + it.outputTokens },
            inputTokens = todayItems.sumOf { it.inputTokens },
            outputTokens = todayItems.sumOf { it.outputTokens },
            estimatedCostUsd = todayItems.sumOf { it.estimatedCostUsd },
            successfulRuns = todayItems.count { it.status == "성공" },
            totalDurationMs = todayItems.sumOf { it.durationMs },
            models = todayItems.filter { it.model !in NON_MODEL_LABELS }
                .groupingBy { it.model }.eachCount()
                .map { (model, runs) -> ModelUsage(model, runs) }
                .sortedByDescending { it.runs },
            // 화면이 기간·기능·모델 필터로 직접 집계하므로 보관 중인 실행을 전부 준다.
            items = items,
        )
    }

    companion object {
        // 모델을 쓰지 않는 실행(수집·템플릿)까지 모델 목록에 넣으면 무엇을 썼는지 흐려진다.
        private val NON_MODEL_LABELS = setOf("모델 사용 안 함", "초안 템플릿")
        // 달력 달 기준. 이번 달 포함 여섯 달을 남겨 "8월 한 달"처럼 지난 달을 통째로 볼 수 있다.
        private const val RETENTION_MONTHS = 6L
        // ponytail: 폭주 방어용 상한. 보관 기간과 별개로, 반복 호출이 문서를 무한히 키우는 것만 막는다.
        private const val MAX_RUNS = 5000
    }

    /** 읽는 곳 한 군데에서 보관 기간을 거른다. record()도 여기를 지나므로 오래된 행은 다음 저장 때 사라진다. */
    private fun load(): List<AiOperationRun> {
        val cutoff = LocalDate.now().withDayOfMonth(1).minusMonths(RETENTION_MONTHS - 1).toString()
        return documents.readList("ai-operations", AiOperationRun::class.java)
            // ISO 문자열이라 앞 10자(YYYY-MM-DD) 비교로 충분하다. 형식이 깨진 시각은 버리지 않는다.
            .filter { it.executedAt.length < 10 || it.executedAt.substring(0, 10) >= cutoff }
            // 정규화 이전에 저장된 기록도 같은 이름으로 합쳐 보인다. 다음 저장 때 그대로 굳는다.
            .map { it.copy(model = LlmClient.canonicalModel(it.model)) }
    }

    private fun save(items: List<AiOperationRun>) = documents.write("ai-operations", items)
}

data class AiOperationRun(
    val id: String,
    val executedAt: String,
    val agent: String,
    val provider: String,
    val model: String,
    val status: String,
    val durationMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double,
    val tools: List<String>,
    val resultPreview: String,
    val error: String? = null,
)

data class AiOperationsOverview(
    val totalRuns: Int,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double,
    val successfulRuns: Int,
    val totalDurationMs: Long,
    val models: List<ModelUsage>,
    val items: List<AiOperationRun>,
)

data class ModelUsage(val model: String, val runs: Int)
