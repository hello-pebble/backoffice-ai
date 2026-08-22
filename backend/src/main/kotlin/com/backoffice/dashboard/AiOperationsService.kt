package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class AiOperationsService(private val objectMapper: ObjectMapper, private val documents: JsonDocumentStore) {
    private val path = Path.of("data/ai-operations/runs.json")

    @Synchronized
    fun record(
        agent: String,
        provider: String,
        model: String,
        tools: List<String>,
        durationMs: Long,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        estimatedCostUsd: Double = 0.0,
        resultPreview: String = "",
        status: String = "성공",
        error: String? = null,
    ) {
        val item = AiOperationRun(
            id = UUID.randomUUID().toString(),
            executedAt = OffsetDateTime.now().toString(),
            agent = agent,
            provider = provider,
            model = model,
            status = status,
            durationMs = durationMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            tools = tools,
            resultPreview = resultPreview.take(240),
            error = error?.take(240),
        )
        save((listOf(item) + load()).take(100))
    }

    @Synchronized
    fun overview(): AiOperationsOverview {
        val items = load().sortedByDescending { it.executedAt }
        val today = LocalDate.now().toString()
        val todayItems = items.filter { it.executedAt.startsWith(today) }
        return AiOperationsOverview(
            totalRuns = todayItems.size,
            totalTokens = todayItems.sumOf { it.inputTokens + it.outputTokens },
            estimatedCostUsd = todayItems.sumOf { it.estimatedCostUsd },
            successfulRuns = todayItems.count { it.status == "성공" },
            items = items.take(20),
        )
    }

    private fun load(): List<AiOperationRun> = documents.readList("ai-operations", AiOperationRun::class.java)
    private fun save(items: List<AiOperationRun>) = documents.write("ai-operations", items)
}

data class AiOperationRun(
    val id: String,
    val executedAt: String,
    val agent: String,
    val provider: String,
    val model: String,
    val status: String,
    val durationMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double,
    val tools: List<String>,
    val resultPreview: String,
    val error: String? = null,
)

data class AiOperationsOverview(
    val totalRuns: Int,
    val totalTokens: Long,
    val estimatedCostUsd: Double,
    val successfulRuns: Int,
    val items: List<AiOperationRun>,
)
