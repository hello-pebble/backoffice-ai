package com.backoffice.dashboard

import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 컷 프롬프트로 이미지를 만든다. 4~8장이면 1~3분이라 요청 스레드에서 기다리지 않는다.
 * 요청은 행을 '생성중'으로 잡고 바로 202 로 끝나고, 백그라운드 스레드가 한 장씩 채운다.
 * 화면은 이미 부르던 목록 API 에 실려 오는 컷 상태를 보고 갱신한다(폴링 전용 엔드포인트 없음).
 *
 * ponytail: 단일 스레드라 여러 툰 요청이 줄 서서 처리된다(4컷 × 약 10초 = 40초).
 * 동시 처리량이 문제되면 newFixedThreadPool(2) 로 올려라. 그 이상은 Imagen 쿼터가 먼저 막는다.
 */
@Service
class ToonImageService(
    private val repository: ToonImageRepository,
    private val toons: InstagramToonService,
    private val llm: LlmClient,
    private val aiOperations: AiOperationsService,
    private val properties: OfficeProperties,
    private val pool: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "toon-image").apply { isDaemon = true }
    },
) {
    fun enqueue(toonId: String): List<ToonImageStatus> {
        val toon = toons.list().firstOrNull { it.id == toonId }
            ?: throw IllegalArgumentException("대본을 찾을 수 없습니다.")
        val prompts = toon.panels.filter { it.imagePrompt.isNotBlank() }.associate { it.number to it.imagePrompt }
        require(prompts.isNotEmpty()) { "이미지 프롬프트가 없는 대본입니다." }
        val owner = DemoContext.owner()
        // ThreadLocal 은 백그라운드 스레드로 따라가지 않는다. 요청 스레드에 있는 지금 꺼내 둔다.
        val sessionKey = DemoContext.sessionKey()
        val stale = properties.llm.imageStaleMinutes

        val pending = repository.enqueue(toonId, owner, prompts.keys.sorted(), stale)
        // 예산은 enqueue 뒤에 센다. 앞에 두면 이미 완료된 컷까지 세어 실제보다 많이 깎인다.
        if (pending.isNotEmpty()) {
            DemoBudget.consumeImages(
                sessionKey, pending.size,
                properties.llm.imageDailyLimit, properties.demo.imageDailyLimit, properties.demo.imageSessionLimit,
            )
            pool.submit { runBatch(toonId, sessionKey, pending.map { (id, panel) -> id to prompts.getValue(panel) }) }
        }
        return repository.statusOfAll(listOf(toonId), owner, stale)[toonId].orEmpty()
    }

    fun bytes(id: Long): Pair<String, ByteArray>? = repository.bytesOf(id, DemoContext.owner())

    private fun runBatch(toonId: String, sessionKey: String?, pending: List<Pair<Long, String>>) {
        // DemoMode 주석이 경고한 지점이다. 여기서 다시 켜지 않으면 아래 record 가 문서 저장소를 타면서
        // 데모 방문자의 실행 기록이 주인 운영 센터에 섞인다.
        sessionKey?.let { DemoContext.set(it) }
        val startedAt = System.nanoTime()
        val model = properties.llm.imageModel
        var done = 0
        var firstError: String? = null
        try {
            for ((id, prompt) in pending) {
                try {
                    val image = llm.image(prompt, model)
                    require(image.bytes.size <= properties.llm.imageMaxBytes) {
                        "이미지가 너무 큽니다(${image.bytes.size}바이트)."
                    }
                    repository.complete(id, image.mimeType, image.bytes)
                    done++
                } catch (error: Exception) {
                    // 한 컷이 죽어도 나머지는 계속 돈다.
                    val reason = LlmClient.reasonOf(error)
                    repository.fail(id, reason)
                    if (firstError == null) firstError = reason
                }
            }
            // 컷마다 기록하면 8컷에 8줄이 쌓여 운영 센터가 이미지로 도배된다. 배치당 한 줄만 남긴다.
            aiOperations.record(
                agent = "인스타툰 이미지 에이전트",
                provider = "Google Imagen",
                model = model,
                tools = listOf("Imagen predict", "${pending.size}컷"),
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                estimatedCostUsd = llm.imageCostUsd(done),
                resultPreview = "${pending.size}컷 중 ${done}컷 이미지를 만들었습니다. (툰 $toonId)",
                status = if (firstError == null) "성공" else "실패",
                error = firstError,
            )
        } finally {
            DemoContext.clear()
        }
    }
}
