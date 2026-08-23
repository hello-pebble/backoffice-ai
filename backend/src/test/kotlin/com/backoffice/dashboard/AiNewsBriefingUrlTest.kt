package com.backoffice.dashboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AiNewsBriefingUrlTest {
    private fun url(base: String) = AiNewsBriefingService.chatCompletionsUrl(base)

    @Test
    fun `버전 경로가 이미 있으면 그대로 이어 붙인다`() {
        // 워커의 OPENAI_BASE_URL 과 같은 값을 넣는 경우. /v1 을 또 붙이면 404 가 난다.
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", url("https://integrate.api.nvidia.com/v1"))
        assertEquals("https://api.deepseek.com/v1/chat/completions", url("https://api.deepseek.com/v1"))
    }

    @Test
    fun `호스트만 적으면 v1 을 보완한다`() {
        assertEquals("https://api.openai.com/v1/chat/completions", url("https://api.openai.com"))
        assertEquals("https://api.deepseek.com/v1/chat/completions", url("https://api.deepseek.com"))
    }

    @Test
    fun `끝의 슬래시와 공백은 무시한다`() {
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", url("  https://integrate.api.nvidia.com/v1/  "))
        assertEquals("https://api.openai.com/v1/chat/completions", url("https://api.openai.com/"))
    }

    @Test
    fun `기본값은 기존 OpenAI 주소를 유지한다`() {
        assertEquals("https://api.openai.com/v1/chat/completions", url(OfficeProperties.AiNews().openAiBaseUrl))
    }
}
