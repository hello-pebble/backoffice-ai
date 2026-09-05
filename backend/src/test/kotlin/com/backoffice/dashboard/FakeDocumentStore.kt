package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate

/** JdbcTemplate 없이 write/read 왕복만 재현하는 대역. */
internal class FakeDocumentStore : JsonDocumentStore(mock(JdbcTemplate::class.java), ObjectMapper()) {
    private val saved = mutableMapOf<String, Any>()

    override fun write(key: String, value: Any) {
        saved[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> read(key: String, type: Class<T>): T? = saved[key] as T?

    @Suppress("UNCHECKED_CAST")
    override fun <T> readList(key: String, elementType: Class<T>): List<T> = saved[key] as? List<T> ?: emptyList()
}

/**
 * 보낸 알림을 모아 두는 Slack 대역.
 * Mockito 로는 Kotlin 의 non-null 반환을 스텁하지 않으면 NPE 가 나고 ArgumentCaptor 도 터진다.
 */
internal class RecordingSlackService : SlackService(OfficeProperties(), ObjectMapper(), FakeDocumentStore()) {
    val sent = mutableListOf<String>()
    var thrown: RuntimeException? = null

    override fun notify(text: String): Pair<String, String?> {
        sent += text
        thrown?.let { throw it }
        return "NOT_CONFIGURED" to null
    }
}
