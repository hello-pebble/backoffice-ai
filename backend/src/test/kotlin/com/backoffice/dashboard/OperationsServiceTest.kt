package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationsServiceTest {
    private val documents = mock(JsonDocumentStore::class.java)
    private val jdbc = mock(JdbcTemplate::class.java)

    // 스텁하지 않으면 documents.read 는 null, jdbc.query 는 빈 목록 = 아무것도 없는 저장소 상태.
    private fun service(seed: Boolean) = OperationsService(documents, jdbc, seed)

    // Mockito 매처는 Kotlin 의 non-null 파라미터에 null 을 넘겨 터지므로 호출 기록을 직접 센다.
    private fun writeCount() = mockingDetails(documents).invocations.count { it.method.name == "write" }

    @Test
    fun `샘플 데이터 시딩이 꺼져 있으면 빈 데이터를 반환하고 저장소에 쓰지 않는다`() {
        val snapshot = service(seed = false).snapshot()

        assertFalse(snapshot.isSample)
        assertTrue(snapshot.tasks.isEmpty())
        assertTrue(snapshot.approvals.isEmpty())
        assertEquals(0, snapshot.kpi.revenue)
        assertEquals(0, writeCount())
    }

    @Test
    fun `샘플 데이터 시딩이 켜져 있으면 샘플을 만들어 저장한다`() {
        val snapshot = service(seed = true).snapshot()

        assertTrue(snapshot.isSample)
        assertEquals(2, snapshot.approvals.size)
        assertEquals(128_400_000, snapshot.kpi.revenue)
        assertEquals(1, writeCount())
    }
}
