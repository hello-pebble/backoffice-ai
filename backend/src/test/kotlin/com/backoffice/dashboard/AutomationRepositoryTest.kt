package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 워커가 직접 쓰던 SQL 을 백엔드로 옮겼으므로, 그 SQL 이 실제 스키마에서 도는지 확인한다.
 * 파이썬 쪽에는 이런 검증이 없었고 마이그레이션이 바뀌면 배포 후에야 터졌다.
 *
 * 전용 스키마에 실제 마이그레이션을 적용하고 끝나면 지운다. DB 가 없으면 건너뛴다.
 */
class AutomationRepositoryTest {
    private val repository = AutomationRepository(jdbc!!, ObjectMapper())
    // legacy_key 가 uuid 컬럼이라 워커도 uuid4 를 쓴다.
    private val contentId = UUID.randomUUID().toString()

    @BeforeEach
    fun clean() {
        jdbc!!.update("delete from automation_posting_record")
        jdbc!!.update("delete from automation_content")
        jdbc!!.update("delete from automation_keyword")
    }

    @Test
    fun `키워드를 넣고 사용 전 목록으로 돌려받는다`() {
        val id = repository.saveKeyword(SaveKeywordRequest(keyword = "백오피스 자동화", searchVolume = 320, category = "IT", priority = 5))

        val unused = repository.unusedKeywords(10)

        assertEquals(1, unused.size)
        assertEquals(id, unused.first().id)
        assertEquals("백오피스 자동화", unused.first().keyword)
        assertEquals(320, unused.first().searchVolume)
    }

    @Test
    fun `id 를 주면 사용 여부만 갱신하고 목록에서 빠진다`() {
        val id = repository.saveKeyword(SaveKeywordRequest(keyword = "키워드", priority = 1))

        repository.saveKeyword(SaveKeywordRequest(id = id, used = true, priority = 9))

        assertEquals(emptyList(), repository.unusedKeywords(10))
    }

    @Test
    fun `없는 키워드를 갱신하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> { repository.saveKeyword(SaveKeywordRequest(id = 999_999, used = true)) }
    }

    @Test
    fun `우선순위와 검색량 순으로 정렬한다`() {
        repository.saveKeyword(SaveKeywordRequest(keyword = "낮음", searchVolume = 900, priority = 1))
        repository.saveKeyword(SaveKeywordRequest(keyword = "높음", searchVolume = 100, priority = 9))

        assertEquals(listOf("높음", "낮음"), repository.unusedKeywords(10).map { it.keyword })
    }

    @Test
    fun `같은 콘텐츠를 다시 보내면 덮어쓴다`() {
        val request = SaveContentRequest(id = contentId, keyword = "키워드", title = "제목", content = "본문", tags = listOf("태그"))

        repository.saveContent(request)
        repository.saveContent(request.copy(title = "고친 제목", status = "posted"))

        // 워커 재시도가 중복 행을 만들면 발행이 두 번 일어난다.
        assertEquals(1, jdbc!!.queryForObject("select count(*) from automation_content", Int::class.java))
        assertEquals("고친 제목", jdbc!!.queryForObject("select title from automation_content", String::class.java))
    }

    @Test
    fun `발행 기록은 원본 콘텐츠가 있을 때만 남는다`() {
        repository.saveContent(SaveContentRequest(id = contentId, keyword = "키워드", title = "제목", content = "본문"))

        repository.savePostingRecord(SavePostingRecordRequest(id = UUID.randomUUID().toString(), contentId = contentId, blogUrl = "https://blog.example.com/1", status = "success"))

        assertEquals(1, jdbc!!.queryForObject("select count(*) from automation_posting_record", Int::class.java))
        // 없는 글의 발행 기록이 생기면 어떤 글이 나갔는지 추적이 끊긴다.
        assertFailsWith<IllegalArgumentException> {
            repository.savePostingRecord(SavePostingRecordRequest(id = UUID.randomUUID().toString(), contentId = UUID.randomUUID().toString(), status = "success"))
        }
    }

    companion object {
        private const val SCHEMA = "automation_repository_test"
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
