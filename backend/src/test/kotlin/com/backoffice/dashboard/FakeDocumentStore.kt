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
