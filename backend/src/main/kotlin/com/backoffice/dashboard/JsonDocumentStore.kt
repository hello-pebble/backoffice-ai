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
    /**
     * 데모 요청이 만지는 문서는 전부 다른 키로 간다. 세션 문서만 예외다(갈리면 데모 세션을 못 찾는다).
     * 여기 한 곳에서 갈라 두면 나중에 새 문서가 생겨도 자동으로 격리된다. 기본값이 "격리"라 닫히는 쪽으로 틀린다.
     *
     * ponytail: gmail-token-* 도 같이 격리된다. 데모가 GmailService 를 부르는 경로가 없어 지금은 닿지 않지만,
     * 데모 요청이 그 저장소를 처음 만들면 팩토리가 빈 스토어를 캐시해 주인 Gmail 이 재시작까지 빈다.
     * 데모에서 Gmail 을 열게 되면 그때 이 예외 목록을 다시 봐라.
     */
    private fun documentKey(key: String): String =
        if (key != DemoContext.SHARED_KEY && DemoContext.isDemo()) DemoContext.KEY_PREFIX + key else key

    fun <T> read(key: String, type: Class<T>): T? {
        val mapper = RowMapper<T> { resultSet, _ -> objectMapper.readValue(resultSet.getString(1), type) }
        return jdbc.query("select payload::text from app_document where document_key = ? and lifecycle_state = 'active'", mapper, documentKey(key)).firstOrNull()
    }

    fun <T> readList(key: String, elementType: Class<T>): List<T> {
        val type = objectMapper.typeFactory.constructCollectionType(List::class.java, elementType)
        val mapper = RowMapper<List<T>> { resultSet, _ -> objectMapper.readValue(resultSet.getString(1), type) }
        return jdbc.query("select payload::text from app_document where document_key = ? and lifecycle_state = 'active'", mapper, documentKey(key)).firstOrNull() ?: emptyList()
    }

    fun write(key: String, value: Any) {
        jdbc.update(
            """
            insert into app_document (document_key, payload, updated_at, lifecycle_state, removed_at)
            values (?, cast(? as jsonb), now(), 'active', null)
            on conflict (document_key) do update
            set payload = excluded.payload, updated_at = now(), lifecycle_state = 'active', removed_at = null
            """.trimIndent(),
            documentKey(key),
            objectMapper.writeValueAsString(value),
        )
    }
}
