package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentStudioServiceTest {
    private val documents = FakeDocumentStore()
    private val service = ContentStudioService(ObjectMapper(), mock(AiOperationsService::class.java), documents)
    private val source = "아침에 커피를 마시며 오늘 할 일을 정리하는 습관에 대한 이야기입니다."

    @Test
    fun `선택한 채널만큼 초안을 만들고 저장한다`() {
        val result = service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "블로그")))

        assertEquals(listOf("인스타툰", "블로그"), result.outputs.map { it.channel })
        assertTrue(result.outputs.all { it.body.isNotBlank() })
        assertEquals(listOf(result.id), service.list().map { it.id })
    }

    @Test
    fun `모르는 채널은 무시하고 아는 채널만 만든다`() {
        val result = service.create(CreateContentPackageRequest(source = source, channels = listOf("인스타툰", "틱톡", "인스타툰")))

        assertEquals(listOf("인스타툰"), result.outputs.map { it.channel })
    }

    @Test
    fun `원본이 20자 미만이면 거부한다`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.create(CreateContentPackageRequest(source = "짧은 메모", channels = listOf("블로그")))
        }

        assertEquals("원본 콘텐츠를 20자 이상 입력하세요.", error.message)
    }

    @Test
    fun `아는 채널이 하나도 없으면 거부한다`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.create(CreateContentPackageRequest(source = source, channels = listOf("틱톡")))
        }

        assertEquals("만들 콘텐츠 채널을 하나 이상 선택하세요.", error.message)
    }

    @Test
    fun `톤과 대상이 비어 있으면 기본값을 채운다`() {
        val result = service.create(CreateContentPackageRequest(source = source, tone = "", target = "", channels = listOf("블로그")))

        assertEquals("공감형", result.tone)
        assertEquals("관심 고객", result.target)
    }

    @Test
    fun `새 패키지가 목록 앞에 쌓인다`() {
        val first = service.create(CreateContentPackageRequest(source = source, channels = listOf("블로그")))
        val second = service.create(CreateContentPackageRequest(source = source, channels = listOf("카드뉴스")))

        assertEquals(listOf(second.id, first.id), service.list().map { it.id })
    }
}
