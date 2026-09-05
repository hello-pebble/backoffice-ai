package com.backoffice.dashboard

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DemoBudgetTest {
    @BeforeEach
    fun reset() = DemoBudget.reset()

    @Test
    fun `세션 한도를 넘으면 막고 남은 하루 한도는 다른 세션이 쓴다`() {
        repeat(3) { DemoBudget.consume("session-a", dailyLimit = 30, sessionLimit = 3) }

        val error = assertFailsWith<IllegalArgumentException> {
            DemoBudget.consume("session-a", dailyLimit = 30, sessionLimit = 3)
        }
        assertTrue(error.message!!.contains("3 회"), "한도 숫자가 문구에 있어야 한다: ${error.message}")

        // 한 방문자가 막혔다고 다른 방문자까지 막히면 안 된다.
        DemoBudget.consume("session-b", dailyLimit = 30, sessionLimit = 3)
    }

    @Test
    fun `하루 한도를 넘으면 새 세션도 막힌다`() {
        repeat(2) { DemoBudget.consume("session-$it", dailyLimit = 2, sessionLimit = 3) }

        val error = assertFailsWith<IllegalArgumentException> {
            DemoBudget.consume("session-새것", dailyLimit = 2, sessionLimit = 3)
        }
        assertTrue(error.message!!.contains("오늘"), "하루 한도 문구여야 한다: ${error.message}")
    }

    @Test
    fun `리셋하면 다시 쓸 수 있다`() {
        DemoBudget.consume("session-a", dailyLimit = 1, sessionLimit = 1)
        assertFailsWith<IllegalArgumentException> { DemoBudget.consume("session-a", dailyLimit = 1, sessionLimit = 1) }

        DemoBudget.reset()

        DemoBudget.consume("session-a", dailyLimit = 1, sessionLimit = 1)
        assertEquals(Unit, Unit)
    }
}
