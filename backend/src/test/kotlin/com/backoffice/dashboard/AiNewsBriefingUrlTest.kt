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

    @Test
    fun `실패 기록에 쓸 이름은 호스트로, 주소가 깨지면 설정값 그대로 남긴다`() {
        assertEquals("integrate.api.nvidia.com", AiNewsBriefingService.vendorOf(false, url("https://integrate.api.nvidia.com/v1")))
        assertEquals("Ollama 로컬", AiNewsBriefingService.vendorOf(true, "http://127.0.0.1:11434/api/generate"))
        // 값에 공백이 섞이는 실수. host 를 못 뽑아도 무엇을 넣었는지는 보여야 한다.
        assertEquals("htt ps://x/v1", AiNewsBriefingService.vendorOf(false, "htt ps://x/v1"))
    }

    @Test
    fun `우리가 던진 예외는 메시지만, 그 외에는 예외 종류까지 남긴다`() {
        assertEquals("소식을 먼저 수집하세요.", AiNewsBriefingService.reasonOf(IllegalArgumentException("소식을 먼저 수집하세요.")))
        assertEquals("401 응답: nope", AiNewsBriefingService.reasonOf(IllegalStateException("401 응답: nope")))
        // 연결 실패는 메시지가 주소뿐이라 종류가 없으면 무슨 일인지 알 수 없다.
        assertEquals(
            "ConnectException: integrate.api.nvidia.com",
            AiNewsBriefingService.reasonOf(java.net.ConnectException("integrate.api.nvidia.com")),
        )
        assertEquals("RuntimeException: 상세 메시지 없음", AiNewsBriefingService.reasonOf(RuntimeException()))
    }
}
