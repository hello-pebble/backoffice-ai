package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RSS·Atom 파싱과 카테고리 분류, 중복 병합은 소식원만 흉내 내면 전부 검증할 수 있다. */
class AiNewsServiceTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val documents = FakeDocumentStore()
    private val operations = AiOperationsService(ObjectMapper(), documents)

    @AfterEach fun stop() = server.stop(0)

    private fun serve(path: String, xml: String) {
        server.createContext(path) { exchange ->
            val payload = xml.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
    }

    private fun service(vararg sources: String): AiNewsService {
        server.start()
        val properties = OfficeProperties(aiNews = OfficeProperties.AiNews(sources = sources.toList()))
        return AiNewsService(properties, ObjectMapper(), operations, documents)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun rss(vararg items: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>피드</title>${items.joinToString("")}</channel></rss>"""

    private fun item(title: String, link: String, description: String, pubDate: String = "Tue, 25 Aug 2026 09:00:00 GMT") =
        "<item><title>$title</title><link>$link</link><description>$description</description><pubDate>$pubDate</pubDate></item>"

    @Test
    fun `RSS 항목을 제목 주소 요약으로 읽고 카테고리를 붙인다`() {
        serve("/rss", rss(
            item("New GPT model released", "https://example.com/model", "<p>A new <b>model</b> is here.</p>"),
            item("Sora video generation", "https://example.com/video", "New video tool."),
            item("Agent MCP support", "https://example.com/agent", "Computer use for agents."),
            item("Company hires staff", "https://example.com/etc", "Hiring news."),
        ))

        val items = service("테스트|${url("/rss")}").refresh()

        assertEquals(4, items.size)
        val byUrl = items.associateBy { it.url }
        assertEquals("모델", byUrl.getValue("https://example.com/model").category)
        assertEquals("이미지·영상", byUrl.getValue("https://example.com/video").category)
        assertEquals("에이전트", byUrl.getValue("https://example.com/agent").category)
        assertEquals("업계 소식", byUrl.getValue("https://example.com/etc").category)
        // 요약에서 태그를 걷어내고 공백을 정리한다. 안 그러면 화면에 HTML 조각이 그대로 보인다.
        assertEquals("A new model is here.", byUrl.getValue("https://example.com/model").summary)
        assertEquals("테스트", byUrl.getValue("https://example.com/model").source)
    }

    @Test
    fun `Atom 항목은 link 의 href 속성에서 주소를 뽑는다`() {
        serve("/atom", """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Safety research paper</title>
                <link href="https://example.com/atom-1"/>
                <summary>New research on safety.</summary>
                <updated>2026-08-25T09:00:00Z</updated>
              </entry>
            </feed>""")

        val items = service("Atom 소식원|${url("/atom")}").refresh()

        assertEquals(1, items.size)
        assertEquals("https://example.com/atom-1", items.first().url)
        assertEquals("연구·안전", items.first().category)
        assertEquals("2026-08-25T09:00:00Z", items.first().publishedAt)
    }

    @Test
    fun `다시 수집해도 이미 읽은 항목의 상태를 덮어쓰지 않는다`() {
        serve("/rss", rss(item("New GPT model released", "https://example.com/model", "설명")))
        val service = service("테스트|${url("/rss")}")

        val first = service.refresh()
        service.markRead(first.first().id)
        val second = service.refresh()

        // 같은 주소는 같은 id 라 한 건으로 남고, 읽음 표시가 살아 있어야 한다.
        assertEquals(1, second.size)
        assertEquals(first.first().id, second.first().id)
        assertTrue(second.first().read, "다시 수집할 때 기존 항목을 덮어쓰면 읽음 표시가 날아간다")
    }

    @Test
    fun `모든 소식원이 실패하면 운영 센터에 못 가져왔다고 남긴다`() {
        // 등록하지 않은 경로 = 404. 두 소식원 모두 실패하는 상황.
        val items = service("죽은 소식원|${url("/none-1")}", "죽은 소식원2|${url("/none-2")}").refresh()

        assertTrue(items.isEmpty())
        val run = operations.overview().items.single()
        assertTrue(run.resultPreview.contains("가져오지 못했습니다"), "실제 기록: ${run.resultPreview}")
    }

    @Test
    fun `일부만 실패하면 성공한 소식원의 결과를 남긴다`() {
        serve("/rss", rss(item("New GPT model released", "https://example.com/model", "설명")))

        val items = service("살아있는 소식원|${url("/rss")}", "죽은 소식원|${url("/none")}").refresh()

        assertEquals(1, items.size)
        assertTrue(operations.overview().items.single().resultPreview.contains("1건"))
    }
}
