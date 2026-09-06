package com.backoffice.dashboard.content

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

/**
 * 컷 이미지 작업 행의 실제 SQL(중복 방지 키, 재시작 복구, 시도 상한)을 로컬 Postgres 에 실행한다.
 * 운영 데이터를 건드리지 않도록 전용 스키마에 마이그레이션을 적용하고 끝나면 지운다. DB 가 없으면 건너뛴다.
 */
class ToonImageRepositoryTest {
    private val repository = ToonImageRepository(jdbc!!)

    @BeforeEach
    fun clean() {
        jdbc!!.update("delete from toon_image")
    }

    private fun attemptsOf(panel: Int) =
        jdbc!!.queryForObject("select attempts from toon_image where toon_id = 'toon-1' and panel_number = ?", Int::class.java, panel)

    @Test
    fun `재시작 복구는 생성중 컷을 프롬프트와 함께 상한 횟수까지만 다시 잡고 완료 컷은 건드리지 않는다`() {
        val pending = repository.enqueue("toon-1", "demo", mapOf(1 to "첫 컷", 2 to "둘째 컷"), staleMinutes = 10)
        assertEquals(listOf(1, 2), pending.map { it.second })
        repository.complete(pending[1].first, "image/png", ByteArray(3))

        val first = repository.orphaned(maxAttempts = 3)
        assertEquals(listOf(OrphanedImage(pending[0].first, "toon-1", "demo", "첫 컷")), first, "완료된 둘째 컷은 다시 잡지 않는다")
        assertEquals(2, attemptsOf(1))

        assertEquals(1, repository.orphaned(maxAttempts = 3).size, "세 번째 시도까지는 잡는다")
        assertEquals(3, attemptsOf(1))
        assertEquals(emptyList(), repository.orphaned(maxAttempts = 3), "상한에 닿으면 재시작해도 더는 돈을 쓰지 않는다")
    }

    @Test
    fun `같은 컷을 다시 누르면 새 행이 아니라 그 행을 되돌리고 시도 횟수를 1 로 돌린다`() {
        val (id, _) = repository.enqueue("toon-1", "owner", mapOf(1 to "첫 컷"), staleMinutes = 10).single()
        repository.orphaned(maxAttempts = 3)
        repository.fail(id, "안전 필터")

        val again = repository.enqueue("toon-1", "owner", mapOf(1 to "고친 컷"), staleMinutes = 10).single()

        assertEquals(id, again.first, "중복 방지 키 (toon_id, panel_number) 가 같은 행을 돌려준다")
        assertEquals(1, attemptsOf(1))
        assertEquals("고친 컷", jdbc!!.queryForObject("select prompt from toon_image where id = ?", String::class.java, id))
        // 방금 잡힌 생성중 컷은 정체되지 않았으니 다시 누르면 아무것도 돌려주지 않는다(같은 컷에 두 번 돈을 쓰지 않는다).
        assertEquals(emptyList(), repository.enqueue("toon-1", "owner", mapOf(1 to "또 고친 컷"), staleMinutes = 10))
    }

    companion object {
        private const val SCHEMA = "toon_image_test"
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
