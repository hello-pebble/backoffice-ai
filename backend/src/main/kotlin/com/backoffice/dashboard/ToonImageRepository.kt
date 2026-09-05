package com.backoffice.dashboard

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 컷 이미지 행. 문서 저장소를 타지 않으므로 데모 격리가 자동으로 걸리지 않는다.
 * 모든 질의가 owner 를 조건에 넣고, 그 값은 DemoContext 로 서버가 정한다(클라이언트 입력이 아니다).
 */
@Repository
class ToonImageRepository(private val jdbc: JdbcTemplate) {

    /**
     * 만들 컷을 '생성중'으로 잡고 (id, 컷번호)를 돌려준다.
     * 완료된 컷은 되돌리지 않아 결과가 안 나온다 — 이미 성공한 컷에 돈을 다시 쓰지 않는다.
     * 실패했거나 정체된(백엔드가 재시작된) 행만 다시 잡는다.
     */
    fun enqueue(toonId: String, owner: String, panelNumbers: List<Int>, staleMinutes: Long): List<Pair<Long, Int>> =
        panelNumbers.mapNotNull { panel ->
            jdbc.query(
                """
                insert into toon_image (toon_id, panel_number, owner)
                values (?, ?, ?)
                on conflict (toon_id, panel_number) do update
                set status = '생성중', error = null, requested_at = now(),
                    completed_at = null, image_bytes = null, mime_type = null,
                    lifecycle_state = 'active', removed_at = null
                where toon_image.status = '실패'
                   or (toon_image.status = '생성중' and toon_image.requested_at < now() - make_interval(mins => ?))
                returning id, panel_number
                """.trimIndent(),
                { rs, _ -> rs.getLong("id") to rs.getInt("panel_number") },
                toonId, panel, owner, staleMinutes.toInt(),
            ).firstOrNull()
        }

    /** 툰 여러 개의 컷 상태를 한 번에 읽는다(목록 화면이 N+1 로 돌지 않게). */
    fun statusOfAll(toonIds: List<String>, owner: String, staleMinutes: Long): Map<String, List<ToonImageStatus>> {
        if (toonIds.isEmpty()) return emptyMap()
        val placeholders = toonIds.joinToString(",") { "?" }
        val cutoff = OffsetDateTime.now().minusMinutes(staleMinutes)
        return jdbc.query(
            """
            select toon_id, id, panel_number, status, error, requested_at, completed_at
            from toon_image
            where toon_id in ($placeholders) and owner = ? and lifecycle_state = 'active'
            order by panel_number
            """.trimIndent(),
            { rs, _ ->
                val requestedAt = rs.getObject("requested_at", OffsetDateTime::class.java)
                // 정체 판정은 읽을 때만 한다. 행은 건드리지 않아 스위퍼도 스케줄러도 필요 없다.
                val stale = rs.getString("status") == "생성중" && requestedAt.isBefore(cutoff)
                rs.getString("toon_id") to ToonImageStatus(
                    id = rs.getLong("id"),
                    panelNumber = rs.getInt("panel_number"),
                    status = if (stale) "실패" else rs.getString("status"),
                    error = if (stale) "생성이 중단되었습니다. 다시 시도해 주세요." else rs.getString("error"),
                    completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toString(),
                )
            },
            *(toonIds + owner).toTypedArray(),
        ).groupBy({ it.first }, { it.second })
    }

    fun complete(id: Long, mimeType: String, bytes: ByteArray) {
        jdbc.update(
            "update toon_image set status = '완료', mime_type = ?, image_bytes = ?, error = null, completed_at = now() where id = ?",
            mimeType, bytes, id,
        )
    }

    fun fail(id: Long, reason: String) {
        jdbc.update("update toon_image set status = '실패', error = ?, completed_at = now() where id = ?", reason.take(240), id)
    }

    /** 소유자가 다르면 없는 것과 같이 다룬다. 존재 여부도 알려 주지 않는다. */
    fun bytesOf(id: Long, owner: String): Pair<String, ByteArray>? = jdbc.query(
        "select mime_type, image_bytes from toon_image where id = ? and owner = ? and status = '완료' and lifecycle_state = 'active'",
        { rs, _ -> (rs.getString("mime_type") ?: "image/png") to rs.getBytes("image_bytes") },
        id, owner,
    ).firstOrNull()
}

data class ToonImageStatus(
    val id: Long,
    @JsonProperty("panel_number") val panelNumber: Int,
    val status: String,
    val error: String? = null,
    @JsonProperty("completed_at") val completedAt: String? = null,
)
