package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 자동화 워커의 결과를 저장한다. 워커(Python)가 DB 에 직접 쓰던 SQL 을 이쪽으로 옮겼다.
 * 스키마를 아는 곳을 하나로 모아, 마이그레이션이 바뀔 때 파이썬까지 고칠 일을 없앤다.
 */
@Repository
class AutomationRepository(private val jdbc: JdbcTemplate, private val objectMapper: ObjectMapper) {

    /** id 가 있으면 사용 여부·우선순위만 갱신하고, 없으면 새 키워드를 넣는다. */
    fun saveKeyword(request: SaveKeywordRequest): Long {
        val now = OffsetDateTime.now().toString()
        if (request.id != null) {
            return jdbc.query(
                "update automation_keyword set used = ?, priority = ? where id = ? and lifecycle_state = 'active' returning id",
                { rs, _ -> rs.getLong("id") },
                request.used, request.priority, request.id,
            ).firstOrNull() ?: throw IllegalArgumentException("키워드를 찾을 수 없습니다: ${request.id}")
        }
        require(request.keyword.isNotBlank()) { "키워드가 비어 있습니다." }
        return jdbc.queryForObject(
            """
            insert into automation_keyword (keyword, search_volume, category, collected_at, used, priority)
            values (?, ?, ?, cast(? as timestamptz), ?, ?) returning id
            """.trimIndent(),
            Long::class.java,
            request.keyword, request.searchVolume, request.category, request.collectedDate ?: now, request.used, request.priority,
        )!!
    }

    fun unusedKeywords(limit: Int): List<AutomationKeyword> = jdbc.query(
        """
        select id, keyword, search_volume, category, collected_at, used, priority
        from automation_keyword
        where used = false and lifecycle_state = 'active'
        order by priority desc, search_volume desc limit ?
        """.trimIndent(),
        { rs, _ ->
            AutomationKeyword(
                id = rs.getLong("id"),
                keyword = rs.getString("keyword"),
                searchVolume = rs.getInt("search_volume"),
                category = rs.getString("category") ?: "",
                collectedDate = rs.getString("collected_at"),
                used = rs.getBoolean("used"),
                priority = rs.getInt("priority"),
            )
        },
        limit,
    )

    /** 워커가 같은 글을 다시 보내면 덮어쓴다. 재시도가 중복 행을 만들면 안 된다. */
    fun saveContent(request: SaveContentRequest) {
        require(request.id.isNotBlank()) { "콘텐츠 id 가 비어 있습니다." }
        // legacy_key 는 uuid 컬럼이다. 워커가 uuid4 로 만들지만, 다른 형식이 오면 여기서 걸러 준다.
        requireUuid(request.id, "콘텐츠 id")
        jdbc.update(
            """
            insert into automation_content (legacy_key, keyword, title, content, tags, created_at, status, posted_at, lifecycle_state, removed_at)
            values (cast(? as uuid), ?, ?, ?, cast(? as jsonb), cast(? as timestamptz), ?, cast(? as timestamptz), 'active', null)
            on conflict (legacy_key) do update
            set keyword = excluded.keyword, title = excluded.title, content = excluded.content, tags = excluded.tags,
                status = excluded.status, posted_at = excluded.posted_at, lifecycle_state = 'active', removed_at = null
            """.trimIndent(),
            request.id, request.keyword, request.title, request.content,
            objectMapper.writeValueAsString(request.tags),
            request.createdDate ?: OffsetDateTime.now().toString(),
            request.status, request.postedDate,
        )
    }

    /** 발행 기록은 원본 콘텐츠가 있을 때만 남는다. 없는 글의 기록이 생기면 추적이 끊긴다. */
    fun savePostingRecord(request: SavePostingRecordRequest) {
        requireUuid(request.id, "발행 기록 id")
        requireUuid(request.contentId, "콘텐츠 id")
        val inserted = jdbc.update(
            """
            insert into automation_posting_record (legacy_key, content_id, content_legacy_key, blog_url, posted_at, status, error_message, lifecycle_state)
            select cast(? as uuid), content.id, content.legacy_key, ?, cast(? as timestamptz), ?, ?, 'active' from automation_content content
            where content.legacy_key = cast(? as uuid) and content.lifecycle_state = 'active'
            """.trimIndent(),
            request.id, request.blogUrl, request.postedDate ?: OffsetDateTime.now().toString(),
            request.status, request.errorMessage, request.contentId,
        )
        if (inserted != 1) throw IllegalArgumentException("발행할 자동화 콘텐츠를 찾을 수 없습니다: ${request.contentId}")
    }

    private fun requireUuid(value: String, label: String) {
        require(runCatching { java.util.UUID.fromString(value) }.isSuccess) { "$label 가 uuid 형식이 아닙니다: $value" }
    }
}

data class SaveKeywordRequest(
    val id: Long? = null,
    val keyword: String = "",
    val searchVolume: Int = 0,
    val category: String = "",
    val collectedDate: String? = null,
    val used: Boolean = false,
    val priority: Int = 0,
)

data class AutomationKeyword(
    val id: Long,
    val keyword: String,
    val searchVolume: Int,
    val category: String,
    val collectedDate: String?,
    val used: Boolean,
    val priority: Int,
)

data class SaveContentRequest(
    val id: String = "",
    val keyword: String = "",
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val createdDate: String? = null,
    val status: String = "pending",
    val postedDate: String? = null,
)

data class SavePostingRecordRequest(
    val id: String = "",
    val contentId: String = "",
    val blogUrl: String? = null,
    val postedDate: String? = null,
    val status: String = "",
    val errorMessage: String? = null,
)
