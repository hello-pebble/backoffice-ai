package com.backoffice.dashboard

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("office")
data class OfficeProperties(
    val toss: Toss = Toss(),
    val gmail: Gmail = Gmail(),
    val automation: Automation = Automation(),
    val operations: Operations = Operations(),
    val aiNews: AiNews = AiNews(),
    val slack: Slack = Slack(),
    val auth: Auth = Auth(),
    val llm: Llm = Llm(),
) {
    data class Toss(
        val enabled: Boolean = false,
        val baseUrl: String = "https://openapi.tossinvest.com",
        val clientId: String = "",
        val clientSecret: String = "",
        val watchlist: List<String> = listOf("005930", "000660", "373220"),
    )
    data class Gmail(
        val enabled: Boolean = false,
        val credentialsPath: String = "data/office-dashboard/gmail-credentials.json",
        // 배포용. 클라이언트 JSON 원문을 그대로 넣는다. 값이 있으면 credentialsPath보다 우선한다.
        val credentialsJson: String = "",
        // 화면에 띄울 "확인할 메일"의 기준. Gmail 검색 문법을 그대로 쓴다.
        // in:inbox 라서 스팸·휴지통은 처음부터 빠지고, 홍보·소셜 카테고리도 제외한다.
        val query: String = "in:inbox is:unread -category:promotions -category:social",
    )
    data class Automation(
        val pythonExecutable: String = "venv/Scripts/python.exe",
        val workingDirectory: String = ".",
        val executionEnabled: Boolean = true,
        val workerUrl: String = "",
        val workerApiKey: String = "",
    )
    data class Llm(
        val requestTimeoutSeconds: Long = 120,
        // 429·5xx·연결 실패에만 쓰는 재시도 횟수. 4xx 는 다시 보내도 같은 답이라 재시도하지 않는다.
        val maxAttempts: Int = 3,
        val retryDelayMillis: Long = 500,
        // 모델별 100만 토큰 단가. "모델명=입력단가,출력단가" 형식이고 없으면 office.ai-news 기본 단가를 쓴다.
        val prices: Map<String, String> = emptyMap(),
    )
    data class Auth(
        // false 면 인증 없이 모든 API 가 열린다. 로컬 개발 전용.
        val enabled: Boolean = true,
        // 로그인을 허용할 이메일. 비어 있으면 아무도 로그인할 수 없다(닫힌 기본값).
        val allowedEmails: List<String> = emptyList(),
        // Google Cloud 콘솔에 등록한 값과 정확히 같아야 한다. 로그인이 Gmail 동의까지 함께 받는다.
        val redirectUri: String = "http://127.0.0.1:8765/api/auth/callback",
        // 서버 측 세션 상한. 쿠키는 브라우저 세션 쿠키라 브라우저를 닫으면 먼저 끊긴다.
        val sessionHours: Long = 24,
        // 프런트가 다른 도메인(Vercel)에 있으면 secure=true, same-site=None 이어야 쿠키가 붙는다.
        val cookieSecure: Boolean = false,
        val cookieSameSite: String = "Lax",
        // 로그인 성공 후 돌려보낼 화면 주소.
        val successRedirect: String = "http://127.0.0.1:8765/",
    )
    data class Slack(
        // Slack 앱의 OAuth 자격증명. 설치가 끝나면 봇 토큰을 저장해 chat.postMessage 로 보낸다.
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String = "http://127.0.0.1:8765/api/slack/callback",
        val apiBaseUrl: String = "https://slack.com/api",
        // Slack 알림에 넣을 검토 화면 주소. 대본 전문 대신 이 링크만 보낸다.
        val reviewBaseUrl: String = "http://127.0.0.1:8765",
    )
    data class Operations(val dataPath: String = "data/office-dashboard/operations.json")
    data class AiNews(
        val dataPath: String = "data/ai-news/news.json",
        val briefingPath: String = "data/ai-news/briefing.json",
        val summaryProvider: String = "ollama",
        val openAiApiKey: String = "",
        // OpenAI 호환 엔드포인트. 워커의 OPENAI_BASE_URL 과 같은 형식으로, 버전 경로까지 포함해 적는다.
        // 예: NVIDIA NIM https://integrate.api.nvidia.com/v1
        val openAiBaseUrl: String = "https://api.openai.com/v1",
        val summaryModel: String = "gpt-5.6-luna",
        val ollamaBaseUrl: String = "http://127.0.0.1:11434",
        val ollamaModel: String = "llama3.2:1b",
        val inputPricePerMillionUsd: Double = 0.20,
        val outputPricePerMillionUsd: Double = 1.20,
        val sources: List<String> = listOf(
            "OpenAI|https://openai.com/blog/rss/",
            "Google DeepMind|https://deepmind.google/blog/rss.xml",
            "Hugging Face|https://huggingface.co/blog/feed.xml",
        ),
    )
}

