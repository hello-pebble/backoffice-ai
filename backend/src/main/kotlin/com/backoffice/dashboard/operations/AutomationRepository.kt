package com.backoffice.dashboard.operations

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
        // 같은 키워드가 다시 오면(7일 창 트렌드) 새 행 대신 검색량·우선순위만 올리고 used 는 그대로 둔다.
        // 이미 대본을 만든 키워드가 다시 유행해도 또 만들지 않는다.
        return jdbc.queryForObject(
            """
            insert into automation_keyword (keyword, search_volume, category, collected_at, used, priority)
            values (?, ?, ?, cast(? as timestamptz), ?, ?)
            on conflict (lower(keyword)) where lifecycle_state = 'active' do update
            set search_volume = greatest(automation_keyword.search_volume, excluded.search_volume),
                priority = greatest(automation_keyword.priority, excluded.priority),
                collected_at = excluded.collected_at
            returning id
            """.trimIndent(),
            Long::class.java,
            request.keyword, request.searchVolume, request.category, request.collectedDate ?: now, request.used, request.priority,
        )!!
    }

    /** 소진만 표시한다. saveKeyword(id) 는 priority 까지 덮어써서 값을 모르는 호출자가 쓰면 0 이 된다. */
    fun markKeywordUsed(id: Long) {
        jdbc.update("update automation_keyword set used = true where id = ? and lifecycle_state = 'active'", id)
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

    /**
     * 상태만 바꾼다. saveContent 의 upsert 는 keyword·title·content 까지 excluded 로 덮어써서,
     * 상태만 들고 부르면 본문이 빈 문자열이 된다. 승인·반려·발행 완료는 전부 여기로 온다.
     */
    fun updateContentStatus(id: String, status: String, postedDate: String? = null) {
        requireUuid(id, "콘텐츠 id")
        val updated = jdbc.update(
            "update automation_content set status = ?, posted_at = coalesce(cast(? as timestamptz), posted_at) where legacy_key = cast(? as uuid) and lifecycle_state = 'active'",
            status, postedDate, id,
        )
        require(updated == 1) { "콘텐츠를 찾을 수 없습니다: $id" }
    }

    /** 워커가 발행할 글. 승인된 것만 준다(status=approved). */
    fun contentsByStatus(status: String, limit: Int): List<AutomationContent> = jdbc.query(
        """
        select legacy_key, keyword, title, content, tags from automation_content
        where status = ? and lifecycle_state = 'active' order by created_at limit ?
        """.trimIndent(),
        { rs, _ ->
            AutomationContent(
                id = rs.getString("legacy_key"), keyword = rs.getString("keyword"), title = rs.getString("title"),
                content = rs.getString("content"), tags = objectMapper.readValue(rs.getString("tags"), Array<String>::class.java).toList(),
            )
        },
        status, limit,
    )

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

data class AutomationContent(val id: String, val keyword: String, val title: String, val content: String, val tags: List<String>)
data class UpdateContentStatusRequest(val status: String = "", val postedDate: String? = null)

data class SavePostingRecordRequest(
    val id: String = "",
    val contentId: String = "",
    val blogUrl: String? = null,
    val postedDate: String? = null,
    val status: String = "",
    val errorMessage: String? = null,
)
