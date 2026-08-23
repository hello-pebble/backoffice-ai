package com.backoffice.dashboard

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("office")
data class OfficeProperties(
    val toss: Toss = Toss(),
    val gmail: Gmail = Gmail(),
    val automation: Automation = Automation(),
    val operations: Operations = Operations(),
    val aiNews: AiNews = AiNews(),
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
        val redirectUri: String = "http://127.0.0.1:8765/api/gmail/callback",
        val credentialsPath: String = "data/office-dashboard/gmail-credentials.json",
        // 배포용. 클라이언트 JSON 원문을 그대로 넣는다. 값이 있으면 credentialsPath보다 우선한다.
        val credentialsJson: String = "",
    )
    data class Automation(
        val pythonExecutable: String = "venv/Scripts/python.exe",
        val workingDirectory: String = ".",
        val executionEnabled: Boolean = true,
        val workerUrl: String = "",
        val workerApiKey: String = "",
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

