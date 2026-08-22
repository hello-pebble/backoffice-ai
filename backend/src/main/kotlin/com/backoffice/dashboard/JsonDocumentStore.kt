package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class JsonDocumentStore(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun <T> read(key: String, type: Class<T>): T? {
        val mapper = RowMapper<T> { resultSet, _ -> objectMapper.readValue(resultSet.getString(1), type) }
        return jdbc.query("select payload::text from app_documents where document_key = ?", mapper, key).firstOrNull()
    }

    fun <T> readList(key: String, elementType: Class<T>): List<T> {
        val type = objectMapper.typeFactory.constructCollectionType(List::class.java, elementType)
        val mapper = RowMapper<List<T>> { resultSet, _ -> objectMapper.readValue(resultSet.getString(1), type) }
        return jdbc.query("select payload::text from app_documents where document_key = ?", mapper, key).firstOrNull() ?: emptyList()
    }

    fun write(key: String, value: Any) {
        jdbc.update(
            """
            insert into app_documents (document_key, payload, updated_at)
            values (?, cast(? as jsonb), now())
            on conflict (document_key) do update
            set payload = excluded.payload, updated_at = now()
            """.trimIndent(),
            key,
            objectMapper.writeValueAsString(value),
        )
    }
}
