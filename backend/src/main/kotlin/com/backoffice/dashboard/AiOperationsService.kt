package com.backoffice.dashboard

import com.backoffice.dashboard.automation.SlackService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * AI 실행 이력. ai_operation_run 에 한 실행이 한 행이다.
 * 기간·기능·모델 필터와 페이지, 집계를 전부 SQL 이 하므로 화면은 받은 그대로 그린다.
 * 데모 격리는 owner 컬럼이다(문서 저장소를 타지 않아 접두사가 자동으로 붙지 않는다).
 */
@Service
class AiOperationsService(
    private val jdbc: JdbcTemplate,
    private val slack: SlackService,
    private val properties: OfficeProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiOperationsService::class.java)

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
        insert(item, DemoContext.owner())
        if (status != "성공") notifyFailure(item)
    }

    /** 저장은 이 한 곳만 지난다. 테스트 대역이 여기를 덮어 DB 없이 기록을 모은다. */
    protected fun insert(run: AiOperationRun, owner: String) {
        jdbc.update(
            """
            insert into ai_operation_run (legacy_key, owner, executed_at, agent, provider, model, status, duration_ms,
                                          input_tokens, output_tokens, estimated_cost_usd, tools, result_preview, error)
            values (?, ?, cast(? as timestamptz), ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
            on conflict (legacy_key) do nothing
            """.trimIndent(),
            run.id, owner, run.executedAt, run.agent, run.provider, run.model, run.status, run.durationMs,
            run.inputTokens, run.outputTokens, run.estimatedCostUsd, objectMapper.writeValueAsString(run.tools),
            run.resultPreview, run.error,
        )
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

    /**
     * range 는 화면 셀렉트 값 그대로: today · 7d · YYYY-MM · 그 밖(all)은 보관 기간 전체.
     * 지표·모델별 표·목록이 같은 where 절에서 나오므로 서로 어긋날 수 없다.
     */
    fun overview(
        range: String = "today",
        agent: String? = null,
        model: String? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): AiOperationsOverview {
        val owner = DemoContext.owner()
        val zone = ZoneId.systemDefault()
        val floor = LocalDate.now().withDayOfMonth(1).minusMonths(RETENTION_MONTHS - 1)
        val (from, to) = window(range, floor)
        val where = StringBuilder("where owner = ? and lifecycle_state = 'active' and executed_at >= ? and executed_at < ?")
        val args = mutableListOf<Any>(owner, from.atStartOfDay(zone).toOffsetDateTime(), to.atStartOfDay(zone).toOffsetDateTime())
        agent?.let { where.append(" and agent = ?"); args += it }
        model?.let { where.append(" and model = ?"); args += it }
        val filter = args.toTypedArray()

        val metrics = jdbc.queryForMap(
            """
            select count(*) as runs, count(*) filter (where status = '성공') as ok,
                   coalesce(sum(input_tokens), 0) as inp, coalesce(sum(output_tokens), 0) as outp,
                   coalesce(sum(estimated_cost_usd), 0) as cost, coalesce(sum(duration_ms), 0) as dur
            from ai_operation_run $where
            """.trimIndent(), *filter,
        )
        val models = jdbc.query(
            """
            select model, count(*) as runs, sum(input_tokens) as inp, sum(output_tokens) as outp,
                   sum(estimated_cost_usd) as cost, sum(duration_ms) as dur
            from ai_operation_run $where and model not in (${NON_MODEL_LABELS.joinToString { "?" }})
            group by model order by runs desc, model
            """.trimIndent(),
            { rs, _ -> ModelUsage(rs.getString("model"), rs.getInt("runs"), rs.getLong("inp"), rs.getLong("outp"), rs.getDouble("cost"), rs.getLong("dur")) },
            *filter, *NON_MODEL_LABELS.toTypedArray(),
        )
        val items = jdbc.query(
            "select * from ai_operation_run $where order by executed_at desc, id desc limit ? offset ?",
            { rs, _ -> run(rs, zone) },
            *filter, size, page * size,
        )
        // 셀렉트 옵션은 필터와 무관하게 보관 중인 전체에서 뽑는다. 고른 조건 때문에 다른 선택지가 사라지면 안 된다.
        val scope = "from ai_operation_run where owner = ? and lifecycle_state = 'active' and executed_at >= ?"
        val scopeArgs = arrayOf<Any>(owner, floor.atStartOfDay(zone).toOffsetDateTime())
        val distinct = { sql: String -> jdbc.queryForList(sql, String::class.java, *scopeArgs) }
        return AiOperationsOverview(
            totalRuns = (metrics["runs"] as Number).toInt(),
            successfulRuns = (metrics["ok"] as Number).toInt(),
            inputTokens = (metrics["inp"] as Number).toLong(),
            outputTokens = (metrics["outp"] as Number).toLong(),
            totalTokens = (metrics["inp"] as Number).toLong() + (metrics["outp"] as Number).toLong(),
            estimatedCostUsd = (metrics["cost"] as Number).toDouble(),
            totalDurationMs = (metrics["dur"] as Number).toLong(),
            models = models,
            items = items,
            total = (metrics["runs"] as Number).toLong(),
            page = page,
            size = size,
            agents = distinct("select distinct agent $scope order by agent"),
            modelNames = distinct("select distinct model $scope order by model"),
            months = distinct("select distinct to_char(executed_at at time zone '${zone.id}', 'YYYY-MM') as ym $scope order by ym desc"),
        )
    }

    /** [from, to) 날짜 범위. 보관 기간보다 앞은 어떤 range 든 잘라 낸다. */
    private fun window(range: String, floor: LocalDate): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        val (from, to) = when {
            range == "today" -> today to today.plusDays(1)
            range == "7d" -> today.minusDays(6) to today.plusDays(1)
            range.matches(Regex("[0-9]{4}-[0-9]{2}")) -> YearMonth.parse(range).let { it.atDay(1) to it.plusMonths(1).atDay(1) }
            else -> floor to today.plusDays(1)
        }
        return maxOf(from, floor) to to
    }

    private fun run(rs: ResultSet, zone: ZoneId) = AiOperationRun(
        id = rs.getString("legacy_key") ?: rs.getLong("id").toString(),
        // DB 는 UTC 로 돌려준다. 화면은 앞 16자를 그대로 보여 주므로 서버 시간대로 맞춰 준다(예전 문자열과 같은 모양).
        executedAt = rs.getObject("executed_at", OffsetDateTime::class.java).atZoneSameInstant(zone).toOffsetDateTime().toString(),
        agent = rs.getString("agent"),
        provider = rs.getString("provider"),
        model = rs.getString("model"),
        status = rs.getString("status"),
        durationMs = rs.getLong("duration_ms"),
        inputTokens = rs.getLong("input_tokens"),
        outputTokens = rs.getLong("output_tokens"),
        estimatedCostUsd = rs.getDouble("estimated_cost_usd"),
        tools = objectMapper.readerForListOf(String::class.java).readValue(rs.getString("tools")),
        resultPreview = rs.getString("result_preview"),
        error = rs.getString("error"),
    )

    /**
     * 데모 보드의 시작 이력. 다른 데모 씨앗(AuthService)은 문서라 로그인 때 넣지만 이건 행이라 기동 때 넣는다.
     * 같은 id 는 건너뛰므로 재기동해도 한 번만 들어간다. 날짜는 넣는 날 기준이다.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun seedDemo() {
        if (!properties.demo.enabled) return
        val json = javaClass.getResourceAsStream("/demo/ai-operations.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val today = LocalDate.now()
        val filled = json.replace("{{today}}", today.toString()).replace("{{today-4}}", today.minusDays(4).toString())
        objectMapper.readerForListOf(AiOperationRun::class.java).readValue<List<AiOperationRun>>(filled)
            // 씨앗은 주인 실데이터에서 만들어 id 가 주인 행과 같다. 접두사가 없으면 유니크 키에 막혀 하나도 안 들어간다.
            .forEach { insert(it.copy(id = "demo:" + it.id, model = LlmClient.canonicalModel(it.model)), "demo") }
    }

    companion object {
        // 모델을 쓰지 않는 실행(수집·템플릿)까지 모델 목록에 넣으면 무엇을 썼는지 흐려진다.
        private val NON_MODEL_LABELS = listOf("모델 사용 안 함", "초안 템플릿")
        // 달력 달 기준. 이번 달 포함 여섯 달을 남겨 "8월 한 달"처럼 지난 달을 통째로 볼 수 있다.
        private const val RETENTION_MONTHS = 6L
        // 화면의 한 페이지. app.js 의 PAGE_SIZE 와 같다.
        const val PAGE_SIZE = 5
    }
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
    val total: Long,
    val page: Int,
    val size: Int,
    val agents: List<String>,
    val modelNames: List<String>,
    val months: List<String>,
)

data class ModelUsage(
    val model: String,
    val runs: Int,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedCostUsd: Double = 0.0,
    val durationMs: Long = 0,
)
