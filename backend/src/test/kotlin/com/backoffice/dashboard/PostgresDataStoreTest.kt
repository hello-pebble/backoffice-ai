package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Gmail 토큰이 Postgres에 저장되고 새 인스턴스에서 복원되는지 확인한다.
 * 이게 깨지면 재배포마다 재인증을 요구하게 되는데, 화면상으로는
 * "Gmail 연결이 아직 완료되지 않았습니다"와 구분되지 않아 눈치채기 어렵다.
 */
class PostgresDataStoreTest {

    @Test
    fun `저장된 토큰이 새 인스턴스에서 복원된다`() {
        val documents = FakeDocumentStore()

        PostgresDataStoreFactory(documents).getDataStore<String>("creds")
            .set("office-dashboard-user", "리프레시-토큰")

        // 재배포 상황: 새 팩터리 = 빈 메모리 맵. 저장소에서만 복원될 수 있어야 한다.
        val restored = PostgresDataStoreFactory(documents).getDataStore<String>("creds")
        assertEquals("리프레시-토큰", restored.get("office-dashboard-user"))
    }

    @Test
    fun `삭제하면 새 인스턴스에서도 사라진다`() {
        val documents = FakeDocumentStore()

        val first = PostgresDataStoreFactory(documents).getDataStore<String>("creds")
        first.set("office-dashboard-user", "리프레시-토큰")
        first.delete("office-dashboard-user")

        val second = PostgresDataStoreFactory(documents).getDataStore<String>("creds")
        assertNull(second.get("office-dashboard-user"))
    }

    @Test
    fun `데이터스토어 id별로 문서 키가 분리된다`() {
        val documents = FakeDocumentStore()
        val factory = PostgresDataStoreFactory(documents)

        factory.getDataStore<String>("creds").set("k", "A")
        factory.getDataStore<String>("other").set("k", "B")

        assertEquals("A", PostgresDataStoreFactory(documents).getDataStore<String>("creds").get("k"))
        assertEquals("B", PostgresDataStoreFactory(documents).getDataStore<String>("other").get("k"))
    }
}

/** JdbcTemplate 없이 write/read 왕복만 재현하는 대역. */
private class FakeDocumentStore : JsonDocumentStore(mock(JdbcTemplate::class.java), ObjectMapper()) {
    private val saved = mutableMapOf<String, Any>()

    override fun write(key: String, value: Any) {
        saved[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> read(key: String, type: Class<T>): T? = saved[key] as T?
}
