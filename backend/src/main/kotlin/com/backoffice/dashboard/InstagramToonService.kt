package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class InstagramToonService(
    private val properties: OfficeProperties,
    private val objectMapper: ObjectMapper,
    private val aiOperationsService: AiOperationsService,
) {
    fun generate(request: CreateInstagramToonRequest): InstagramToon {
        val startedAt = System.nanoTime()
        check(properties.automation.executionEnabled) { "인스타툰 생성은 OCI 자동화 워커 연결 후 사용할 수 있습니다." }
        require(request.episode.trim().length >= 10) { "에피소드는 10자 이상 입력하세요." }
        require(request.panelCount in setOf(4, 8)) { "컷 수는 4 또는 8만 가능합니다." }
        val id = UUID.randomUUID().toString()
        val process = ProcessBuilder(
            properties.automation.pythonExecutable,
            "-m", "automation.scripts.run_instagram_toon",
            "--id", id,
            "--episode", request.episode.trim(),
            "--tone", request.tone.ifBlank { "공감형" },
            "--panels", request.panelCount.toString(),
        ).directory(File(properties.automation.workingDirectory)).redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("인스타툰 대본 생성 시간이 초과되었습니다.")
        }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() != 0) throw IllegalStateException(output.takeLast(1_500).ifBlank { "대본 생성에 실패했습니다." })
        return read(id).also { toon ->
            aiOperationsService.record(
                agent = "인스타툰 대본 에이전트",
                provider = "Python 자동화",
                model = "INSTAGRAM_TOON_MODEL",
                tools = listOf("Python 대본 생성기", "OpenAI 호환 모델"),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                resultPreview = "${toon.title} · ${toon.panelCount}컷 대본을 만들었습니다.",
            )
        }
    }

    fun list(): List<InstagramToon> {
        val directory = Path.of("data/instagram-toons")
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }
                .map { objectMapper.readValue(it.toFile(), InstagramToon::class.java) }
                .sorted(compareByDescending { it.createdAt })
                .limit(20).toList()
        }
    }

    private fun read(id: String): InstagramToon = objectMapper.readValue(
        Path.of("data/instagram-toons/$id.json").toFile(), InstagramToon::class.java
    )
}

data class CreateInstagramToonRequest(val episode: String = "", val tone: String = "공감형", val panelCount: Int = 4)
data class InstagramToon(val id: String, val episode: String, val tone: String, @JsonProperty("panel_count") val panelCount: Int, val title: String, val caption: String, val hashtags: List<String>, val panels: List<InstagramToonPanel>, @JsonProperty("created_at") val createdAt: String)
data class InstagramToonPanel(val number: Int, val scene: String, val dialogue: String, val narration: String, @JsonProperty("image_prompt") val imagePrompt: String)
