package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JsonDocumentStore 의 실제 SQL 을 로컬 Postgres 에 실행한다.
 * 다른 테스트는 모두 FakeDocumentStore 로 갈아끼우기 때문에, 이 파일이 없으면
 * payload::text · cast(? as jsonb) · on conflict · lifecycle_state 가 한 번도 실행되지 않는다.
 *
 * 운영 데이터를 건드리지 않도록 전용 스키마에 실제 마이그레이션을 적용하고 끝나면 지운다.
 * DB 가 없으면(예: CI) 건너뛴다. 로컬은 docker 의 backoffice-pg 를 그대로 쓴다.
 */
class JsonDocumentStoreTest {
    private val store = JsonDocumentStore(jdbc!!, objectMapper)

    @Test
    fun `객체를 저장하고 같은 값으로 읽어 온다`() {
        store.write("brief", AiNewsSummary("news-1", "한 줄 요약", "업무 영향"))

        assertEquals(AiNewsSummary("news-1", "한 줄 요약", "업무 영향"), store.read("brief", AiNewsSummary::class.java))
    }

    @Test
    fun `목록을 저장하고 목록으로 읽어 온다`() {
        val items = listOf(AiNewsSummary("a", "요약 A", "영향 A"), AiNewsSummary("b", "요약 B", "영향 B"))

        store.write("list", items)

        assertEquals(items, store.readList("list", AiNewsSummary::class.java))
    }

    @Test
    fun `같은 키로 다시 쓰면 행이 늘지 않고 최신 값으로 덮인다`() {
        store.write("overwrite", AiNewsSummary("v1", "이전", "이전"))
        store.write("overwrite", AiNewsSummary("v2", "최신", "최신"))

        assertEquals("v2", store.read("overwrite", AiNewsSummary::class.java)?.id)
        // on conflict 가 빠지면 여기서 유니크 제약 위반이나 중복 행이 된다.
        assertEquals(1, jdbc!!.queryForObject("select count(*) from app_document where document_key = 'overwrite'", Int::class.java))
    }

    @Test
    fun `없는 키는 null 과 빈 목록이다`() {
        assertNull(store.read("없는-키", AiNewsSummary::class.java))
        assertTrue(store.readList("없는-키", AiNewsSummary::class.java).isEmpty())
    }

    @Test
    fun `지운 문서는 읽히지 않고 다시 쓰면 살아난다`() {
        store.write("soft-delete", AiNewsSummary("v1", "요약", "영향"))
        // 삭제는 행을 지우지 않고 lifecycle_state 만 바꾼다(V6 규칙).
        jdbc!!.update("update app_document set lifecycle_state = 'removed', removed_at = now() where document_key = 'soft-delete'")

        assertNull(store.read("soft-delete", AiNewsSummary::class.java), "removed 상태는 조회에서 빠져야 한다")

        store.write("soft-delete", AiNewsSummary("v2", "다시", "영향"))

        assertEquals("v2", store.read("soft-delete", AiNewsSummary::class.java)?.id, "다시 쓰면 active 로 되살아나야 한다")
        assertNull(jdbc!!.queryForObject("select removed_at from app_document where document_key = 'soft-delete'", java.sql.Timestamp::class.java))
    }

    companion object {
        private const val SCHEMA = "json_document_test"
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
