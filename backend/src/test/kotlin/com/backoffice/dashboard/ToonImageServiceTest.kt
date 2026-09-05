package com.backoffice.dashboard

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Mockito 매처는 null 을 돌려주는데 Kotlin 은 논널 파라미터에 null 이 들어가는 걸 막는다.
// 제네릭으로 한 번 감싸면 반환 타입이 플랫폼 타입이 아니게 되어 검사 없이 통과한다.
@Suppress("UNCHECKED_CAST")
private fun <T> anyArg(): T = ArgumentMatchers.any()
private fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value)
@Suppress("UNCHECKED_CAST")
private fun <T> containsArg(text: String): T = ArgumentMatchers.contains(text) as T

/** 제출한 작업을 그 자리에서 실행한다. 백그라운드 동작을 테스트에서 결정적으로 보기 위한 대역. */
private class DirectExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

class ToonImageServiceTest {
    private val repository = mock(ToonImageRepository::class.java)
    private val toons = mock(InstagramToonService::class.java)
    private val llm = mock(LlmClient::class.java)
    private val operations = mock(AiOperationsService::class.java)
    private val properties = OfficeProperties(llm = OfficeProperties.Llm(imagePriceUsd = 0.02, imageMaxBytes = 1_000))

    private fun service(pool: java.util.concurrent.ExecutorService = DirectExecutor()) =
        ToonImageService(repository, toons, llm, operations, properties, pool)

    private fun panel(number: Int) = InstagramToonPanel(number, "장면 $number", "대사", "독백", "prompt $number")

    private val toon = InstagramToon(
        id = "toon-1", episode = "에피소드", tone = "공감형", panelCount = 2, title = "제목",
        caption = "캡션", hashtags = emptyList(), panels = listOf(panel(1), panel(2)),
        createdAt = "2026-09-06T09:00:00+09:00", model = "gpt-test",
    )

    @BeforeEach
    fun setUp() {
        DemoBudget.reset()
        `when`(toons.list()).thenReturn(listOf(toon))
        `when`(repository.statusOfAll(anyList(), anyString(), anyLong())).thenReturn(emptyMap())
    }

    @AfterEach
    fun tearDown() {
        DemoContext.clear()
        DemoBudget.reset()
    }

    private fun queued(vararg panels: Int) {
        `when`(repository.enqueue(anyString(), anyString(), anyList(), anyLong()))
            .thenReturn(panels.map { it.toLong() to it })
    }

    private fun image(bytes: Int) = LlmImage(ByteArray(bytes), "image/png")

    @Test
    fun `데모 요청이면 백그라운드 작업 안에서도 데모로 표시된다`() {
        // 여기서 놓치면 AiOperationsService 가 문서 저장소를 타면서 데모 기록이 주인 쪽에 섞인다.
        queued(1)
        var demoInsideTask: Boolean? = null
        `when`(llm.image(anyString(), anyString())).thenAnswer { demoInsideTask = DemoContext.isDemo(); image(10) }
        DemoContext.set("세션-해시")

        service().enqueue("toon-1")

        assertEquals(true, demoInsideTask, "백그라운드 작업에서 데모 표시가 꺼지면 실데이터를 만진다")
    }

    @Test
    fun `컷 하나가 실패해도 나머지를 계속 만들고 기록은 배치당 한 건이다`() {
        queued(1, 2)
        `when`(llm.image(anyString(), anyString()))
            .thenThrow(IllegalStateException("안전 필터"))
            .thenReturn(image(10))
        `when`(llm.imageCostUsd(anyInt())).thenReturn(0.02)

        service().enqueue("toon-1")

        verify(repository).fail(1L, "안전 필터")
        verify(repository).complete(2L, "image/png", ByteArray(10))
        // 성공한 장수만 비용에 넣는다.
        verify(llm).imageCostUsd(1)
        verify(operations).record(
            anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong(),
            anyDouble(), anyString(), eqArg("실패"), anyArg(),
        )
    }

    @Test
    fun `상한보다 큰 이미지는 저장하지 않는다`() {
        queued(1)
        `when`(llm.image(anyString(), anyString())).thenReturn(image(2_000))

        service().enqueue("toon-1")

        verify(repository, never()).complete(anyLong(), anyString(), anyArg())
        verify(repository).fail(anyLong(), containsArg("너무 큽니다"))
    }

    @Test
    fun `예산을 넘으면 작업을 제출하지 않는다`() {
        queued(1, 2)
        DemoContext.set("세션-해시")

        val error = assertFailsWith<IllegalArgumentException> {
            ToonImageService(
                repository, toons, llm, operations,
                OfficeProperties(demo = OfficeProperties.Demo(imageSessionLimit = 1)),
                DirectExecutor(),
            ).enqueue("toon-1")
        }

        assertTrue(error.message!!.contains("한도"), "실제: ${error.message}")
        verify(llm, never()).image(anyString(), anyString())
    }

    @Test
    fun `되돌릴 컷이 없으면 모델을 부르지 않는다`() {
        // 완료된 컷만 있는 툰에 다시 눌러도 이미 성공한 컷에 돈을 다시 쓰지 않는다.
        queued()

        service().enqueue("toon-1")

        verify(llm, never()).image(anyString(), anyString())
        verify(operations, never()).record(
            anyString(), anyString(), anyString(), anyList(), anyLong(), anyLong(), anyLong(),
            anyDouble(), anyString(), anyString(), anyArg(),
        )
    }

    @Test
    fun `없는 대본이면 거부한다`() {
        `when`(toons.list()).thenReturn(emptyList())

        assertEquals("대본을 찾을 수 없습니다.", assertFailsWith<IllegalArgumentException> { service().enqueue("없음") }.message)
    }
}
