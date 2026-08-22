package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.w3c.dom.Element
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Service
class AiNewsService(private val properties: OfficeProperties, private val objectMapper: ObjectMapper, private val aiOperationsService: AiOperationsService, private val documents: JsonDocumentStore) {
    private val log = LoggerFactory.getLogger(AiNewsService::class.java)
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val path get() = Path.of(properties.aiNews.dataPath)

    @Synchronized fun list(): List<AiNewsItem> = load().sortedByDescending { it.publishedAt ?: it.collectedAt }

    @Synchronized fun refresh(): List<AiNewsItem> {
        val startedAt = System.nanoTime()
        val existing = load().associateBy { it.id }.toMutableMap()
        var failures = 0
        properties.aiNews.sources.forEach { source ->
            val (name, url) = source.split("|", limit = 2).let { it[0] to it[1] }
            runCatching { fetch(name, url) }
                .onFailure { failures++; log.warn("AI 소식원 수집 실패: {}", name, it) }
                .getOrDefault(emptyList())
                .forEach { existing.putIfAbsent(it.id, it) }
        }
        val allFailed = failures > 0 && failures == properties.aiNews.sources.size
        val result = existing.values.sortedByDescending { it.publishedAt ?: it.collectedAt }.take(100)
        save(result)
        aiOperationsService.record(
            agent = "AI 뉴스 수집 에이전트",
            provider = "외부 공식 소식원",
            model = "모델 사용 안 함",
            tools = properties.aiNews.sources.map { "RSS · ${it.substringBefore("|")}" },
            durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            resultPreview = if (allFailed) "공식 AI 소식을 가져오지 못했습니다. 모든 소식원 요청이 실패했습니다." else "공식 AI 소식 ${result.size}건을 확인했습니다.",
        )
        return result
    }

    @Synchronized fun markRead(id: String): List<AiNewsItem> {
        val result = load().map { if (it.id == id) it.copy(read = true) else it }
        save(result); return result
    }

    private fun fetch(source: String, url: String): List<AiNewsItem> {
        val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "OfficeDashboard/1.0").GET().build()
        val xml = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(StandardCharsets.UTF_8))
        val rssItems = document.getElementsByTagName("item")
        val atomEntries = document.getElementsByTagName("entry")
        val elements = if (rssItems.length > 0) (0 until rssItems.length).map { rssItems.item(it) as Element } else (0 until atomEntries.length).map { atomEntries.item(it) as Element }
        return elements.take(30).mapNotNull { element ->
            val title = text(element, "title") ?: return@mapNotNull null
            val link = if (element.tagName == "entry") element.getElementsByTagName("link").item(0)?.attributes?.getNamedItem("href")?.nodeValue else text(element, "link")
            if (link.isNullOrBlank()) return@mapNotNull null
            val summary = (text(element, "description") ?: text(element, "summary") ?: text(element, "content") ?: "").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(420)
            val published = text(element, "pubDate") ?: text(element, "published") ?: text(element, "updated")
            AiNewsItem(hash(link), source, title.trim(), link.trim(), summary, published, category("$title $summary"), false, OffsetDateTime.now().toString())
        }
    }

    private fun text(element: Element, tag: String): String? = element.getElementsByTagName(tag).item(0)?.textContent
    private fun category(value: String): String = when {
        Regex("image|video|sora|visual|vision", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "이미지·영상"
        Regex("agent|codex|computer use|mcp", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "에이전트"
        Regex("model|gpt|gemini|llama|claude", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "모델"
        Regex("research|paper|science|safety", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "연구·안전"
        else -> "업계 소식"
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun load(): List<AiNewsItem> = documents.readList("ai-news", AiNewsItem::class.java)
    private fun save(items: List<AiNewsItem>) { documents.write("ai-news", items) }
}

data class AiNewsItem(val id: String, val source: String, val title: String, val url: String, val summary: String, val publishedAt: String?, val category: String, val read: Boolean, val collectedAt: String)
