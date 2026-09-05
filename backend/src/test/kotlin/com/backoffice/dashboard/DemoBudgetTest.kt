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
    fun `이미지는 주인 카운터와 데모 카운터가 서로를 막지 않는다`() {
        // 주인이 전체 상한 안에서 6장을 쓴다.
        DemoBudget.consumeImages(null, 6, dailyLimit = 30, demoDailyLimit = 8, demoSessionLimit = 4)

        // 데모는 자기 카운터가 비어 있으므로 그대로 쓸 수 있어야 한다. 합치면 여기서 막힌다.
        DemoBudget.consumeImages("세션-a", 4, dailyLimit = 30, demoDailyLimit = 8, demoSessionLimit = 4)

        val session = assertFailsWith<IllegalArgumentException> {
            DemoBudget.consumeImages("세션-a", 1, dailyLimit = 30, demoDailyLimit = 8, demoSessionLimit = 4)
        }
        assertTrue(session.message!!.contains("데모 세션"), "실제: ${session.message}")
    }

    @Test
    fun `이미지는 장수를 통째로 세어 반쯤 통과하지 않는다`() {
        val error = assertFailsWith<IllegalArgumentException> {
            // 남은 자리가 2장인데 4컷을 요청하면 2장만 통과시키지 않고 통째로 막는다.
            DemoBudget.consumeImages(null, 4, dailyLimit = 2, demoDailyLimit = 8, demoSessionLimit = 4)
        }
        assertTrue(error.message!!.contains("이미지 한도"), "실제: ${error.message}")

        // 막혔으면 카운터도 늘지 않아야 다음 요청이 정상적으로 들어간다.
        DemoBudget.consumeImages(null, 2, dailyLimit = 2, demoDailyLimit = 8, demoSessionLimit = 4)
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
