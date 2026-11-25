package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 이력의 실제 SQL(필터·페이지·집계·데모 격리)을 로컬 Postgres 에 실행한다.
 * 운영 데이터를 건드리지 않도록 전용 스키마에 마이그레이션을 적용하고 끝나면 지운다. DB 가 없으면 건너뛴다.
 */
class AiOperationsServiceTest {
    private val slack = RecordingSlackService()
    private val service = AiOperationsService(
        jdbc!!, slack,
        OfficeProperties(slack = OfficeProperties.Slack(reviewBaseUrl = "https://office.example.com")),
        objectMapper,
    )

    @BeforeEach
    fun clean() {
        jdbc!!.update("delete from ai_operation_run")
    }

    @AfterEach
    fun clearDemoContext() = DemoContext.clear()

    private fun insertAt(id: String, executedAt: String) = jdbc!!.update(
        """
        insert into ai_operation_run (legacy_key, owner, executed_at, agent, provider, model, status, duration_ms,
                                      input_tokens, output_tokens, estimated_cost_usd, tools, result_preview)
        values (?, 'owner', cast(? as timestamptz), '브리핑', 'openai', 'gpt', '성공', 1, 0, 0, 0, '[]', '')
        """.trimIndent(), id, executedAt,
    )

    @Test
    fun `오늘 실행의 모델별 횟수와 작업 시간을 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt-5.6-luna", tools = emptyList(), durationMs = 1_500)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt-5.6-luna", tools = emptyList(), durationMs = 2_500)
        service.record(agent = "요약", provider = "Ollama 로컬", model = "llama3.2:1b", tools = emptyList(), durationMs = 6_000)
        // 수집 실행은 모델을 쓰지 않으므로 모델 목록에서 빠진다. 시간은 그대로 합산한다.
        service.record(agent = "뉴스 수집", provider = "RSS", model = "모델 사용 안 함", tools = emptyList(), durationMs = 1_000)

        val overview = service.overview()

        assertEquals(11_000, overview.totalDurationMs)
        assertEquals(4, overview.totalRuns)
        assertEquals(listOf("gpt-5.6-luna" to 2, "llama3.2:1b" to 1), overview.models.map { it.model to it.runs })
        assertEquals(4_000, overview.models.first().durationMs)
    }

    @Test
    fun `표기만 다른 같은 모델은 한 이름으로 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "OpenAI/GPT-4o ", tools = emptyList(), durationMs = 1)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt-4o", tools = emptyList(), durationMs = 1)

        assertEquals(listOf("gpt-4o" to 2), service.overview().models.map { it.model to it.runs })
    }

    @Test
    fun `오늘 입력·출력 토큰을 따로 합산한다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1, inputTokens = 400, outputTokens = 100)
        service.record(agent = "대본 초안", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1, inputTokens = 600, outputTokens = 250)

        val overview = service.overview()

        assertEquals(1_000, overview.inputTokens)
        assertEquals(350, overview.outputTokens)
        assertEquals(1_350, overview.totalTokens)
    }

    @Test
    fun `기능·모델 필터와 페이지는 DB 가 처리하고 셀렉트 옵션은 전체에서 뽑는다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = listOf("도구 A"), durationMs = 1)
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1)
        service.record(agent = "대본 초안", provider = "google", model = "gemini", tools = emptyList(), durationMs = 1)

        val filtered = service.overview(agent = "브리핑", model = "gpt", size = 1)

        assertEquals(2, filtered.total)
        assertEquals(1, filtered.items.size, "size=1 이면 한 건만 온다")
        assertEquals(1, service.overview(agent = "브리핑", size = 1, page = 1).items.size)
        assertEquals(0, service.overview(agent = "브리핑", size = 1, page = 2).items.size)
        // 필터를 걸어도 다른 선택지가 사라지면 안 된다.
        assertEquals(listOf("대본 초안", "브리핑"), filtered.agents)
        assertEquals(listOf("gemini", "gpt"), filtered.modelNames)
        assertEquals(listOf(LocalDate.now().toString().substring(0, 7)), filtered.months)
        // 도구 목록은 jsonb 로 갔다가 그대로 돌아온다. 가장 먼저 기록한 실행이 마지막 페이지다.
        assertEquals(listOf("도구 A"), service.overview(agent = "브리핑", page = 1, size = 1).items.single().tools)
    }

    @Test
    fun `데모 실행은 데모에게만 보이고 주인 이력에 섞이지 않는다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1)
        DemoContext.set("세션-해시")
        service.record(agent = "데모 브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1)

        assertEquals(listOf("데모 브리핑"), service.overview().items.map { it.agent })
        DemoContext.clear()
        assertEquals(listOf("브리핑"), service.overview().items.map { it.agent })
    }

    @Test
    fun `여섯 달째 달 1일보다 오래된 실행은 어떤 기간을 골라도 빠진다`() {
        val firstKeptDay = LocalDate.now().withDayOfMonth(1).minusMonths(5)
        val offset = ZoneId.systemDefault().rules.getOffset(firstKeptDay.atStartOfDay())
        insertAt("too-old", firstKeptDay.minusDays(1).atStartOfDay().toString() + offset)
        insertAt("boundary", firstKeptDay.atStartOfDay().toString() + offset)
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 1)

        val ids = service.overview(range = "all").items.map { it.id }

        assertEquals(2, ids.size)
        assertTrue("too-old" !in ids, "여섯 달째 달 1일 이전은 사라져야 한다")
        assertTrue("boundary" in ids)
        assertEquals(1, service.overview(range = "today").items.size)
    }

    @Test
    fun `실패는 Slack 으로 알리고 성공은 알리지 않는다`() {
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 10)
        assertEquals(emptyList(), slack.sent, "성공까지 알리면 알림이 소음이 된다")

        service.record(
            agent = "주제 대본 초안 에이전트", provider = "integrate.api.nvidia.com", model = "deepseek",
            tools = emptyList(), durationMs = 10, status = "실패", error = "request timed out",
        )

        // 무엇이·어디서·왜 실패했는지와 어디를 볼지가 들어가야 한다.
        val message = slack.sent.single()
        listOf("주제 대본 초안 에이전트", "integrate.api.nvidia.com", "request timed out", "https://office.example.com/#ai-operations")
            .forEach { assertEquals(true, message.contains(it), "빠진 내용: $it / 실제: $message") }
    }

    @Test
    fun `알림이 터져도 원래 실패 사유를 덮지 않는다`() {
        slack.thrown = RuntimeException("slack down")

        // 여기서 예외가 새면 호출자의 catch 가 Slack 오류를 원래 사유로 착각한다.
        service.record(agent = "브리핑", provider = "openai", model = "gpt", tools = emptyList(), durationMs = 10, status = "실패", error = "boom")

        assertEquals("boom", service.overview().items.single().error)
    }

    companion object {
        private const val SCHEMA = "ai_operations_test"
        private val objectMapper = ObjectMapper().registerKotlinModule()
        private var jdbc: JdbcTemplate? = null
        private var dataSource: DataSource? = null

        private fun dataSource(schema: String?) = DriverManagerDataSource(
            System.getenv("SUPABASE_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/backoffice",
            System.getenv("SUPABASE_DB_USER") ?: "postgres",
            System.getenv("SUPABASE_DB_PASSWORD") ?: "postgres",
        ).apply {
            setDriverClassName("org.postgresql.Driver")
            schema?.let { setSchema(it) }
        }

        @BeforeAll
        @JvmStatic
        fun migrate() {
            val plain = dataSource(schema = null)
            val reachable = runCatching { plain.connection.use { it.isValid(3) } }.getOrElse { false }
            assumeTrue(reachable, "로컬 Postgres 에 연결할 수 없어 건너뜁니다. docker 의 backoffice-pg 를 띄운 뒤 다시 실행하세요.")
            Flyway.configure().dataSource(plain).schemas(SCHEMA).cleanDisabled(false).load().apply {
                clean()
                migrate()
            }
            dataSource = plain
            jdbc = JdbcTemplate(dataSource(schema = SCHEMA))
        }

        @AfterAll
        @JvmStatic
        fun dropSchema() {
            dataSource?.let { JdbcTemplate(it).execute("drop schema if exists $SCHEMA cascade") }
        }
    }
}
