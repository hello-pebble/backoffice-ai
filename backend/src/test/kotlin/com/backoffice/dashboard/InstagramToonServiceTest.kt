package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 모델을 부르기 전에 걸러야 하는 입력과, 돌아온 JSON 을 화면이 쓸 모양으로 바꾸는 규칙을 검증한다.
 * 입력 검사가 빠지면 잘못된 요청으로 모델 호출 비용만 나간다.
 */
class InstagramToonServiceTest {
    private val documents = FakeDocumentStore()
    private val llm = mock(LlmClient::class.java)
    private val operations = mock(AiOperationsService::class.java)
    private val service = InstagramToonService(ObjectMapper(), operations, llm, documents)

    private val request = CreateInstagramToonRequest(episode = "출근길에 우산을 두고 온 날 이야기", panelCount = 4)

    private fun panels(count: Int) = (1..count).joinToString(",") {
        """{"number":$it,"scene":"장면 $it","dialogue":"대사 $it","narration":"독백 $it","image_prompt":"korean webtoon style, scene $it"}"""
    }

    private fun answer(content: String) {
        val target = LlmTarget(useOllama = false, endpoint = "https://api.example.com/v1/chat/completions", model = "gpt-test", vendor = "api.example.com")
        `when`(llm.target()).thenReturn(target)
        `when`(llm.chat(anyString(), anyString(), anyBoolean())).thenReturn(LlmResponse(content, 100, 200, target, 0.001))
    }

    private fun anyString() = org.mockito.ArgumentMatchers.anyString()
    private fun anyBoolean() = org.mockito.ArgumentMatchers.anyBoolean()

    @Test
    fun `에피소드가 10자 미만이면 모델을 부르지 않는다`() {
        val error = assertFailsWith<IllegalArgumentException> { service.generate(request.copy(episode = "짧아요")) }

        assertEquals("에피소드는 10자 이상 입력하세요.", error.message)
        verify(llm, never()).chat(anyString(), anyString(), anyBoolean())
    }

    @Test
    fun `컷 수는 4 또는 8 만 받는다`() {
        val error = assertFailsWith<IllegalArgumentException> { service.generate(request.copy(panelCount = 5)) }

        assertEquals("컷 수는 4 또는 8만 가능합니다.", error.message)
        verify(llm, never()).chat(anyString(), anyString(), anyBoolean())
    }

    @Test
    fun `대본과 컷별 이미지 프롬프트를 만들어 저장한다`() {
        answer("""{"title":"우산을 두고 온 날","caption":"오늘의 기록","hashtags":["#일상","#공감"],"panels":[${panels(4)}]}""")

        val toon = service.generate(request)

        assertEquals("우산을 두고 온 날", toon.title)
        assertEquals(4, toon.panels.size)
        assertEquals("korean webtoon style, scene 1", toon.panels.first().imagePrompt)
        assertTrue(toon.panels.all { it.imagePrompt.isNotBlank() }, "컷마다 이미지 프롬프트가 있어야 한다")
        // 저장까지 끝나야 목록 조회에서 보인다.
        assertEquals(listOf(toon.id), service.list().map { it.id })
    }

    @Test
    fun `요청한 컷 수와 다르면 실패로 돌린다`() {
        // 자리표시자로 채우면 화면이 요청한 구성과 어긋난 대본을 진짜로 오해한다.
        answer("""{"title":"제목","caption":"","hashtags":[],"panels":[${panels(3)}]}""")

        val error = assertFailsWith<IllegalStateException> { service.generate(request) }

        assertTrue(error.message!!.contains("4컷과 다른 결과"), "실제: ${error.message}")
        assertEquals(emptyList(), service.list(), "실패한 대본은 저장하지 않는다")
    }

    @Test
    fun `설명이 붙어 와도 첫 중괄호 블록을 다시 읽는다`() {
        answer("""여기 대본입니다: {"title":"제목","caption":"","hashtags":[],"panels":[${panels(4)}]} 이상입니다.""")

        assertEquals("제목", service.generate(request).title)
    }
}
