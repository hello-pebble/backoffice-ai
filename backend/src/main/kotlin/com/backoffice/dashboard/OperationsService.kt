package com.backoffice.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OperationsService(private val properties: OfficeProperties, private val objectMapper: ObjectMapper, private val documents: JsonDocumentStore, private val jdbc: JdbcTemplate) {
    private val path get() = Path.of(properties.operations.dataPath)

    @Synchronized fun snapshot(): OperationsData = load().copy(tasks = loadTasks().toMutableList())

    @Synchronized fun createTask(request: CreateTaskRequest): OperationsData {
        require(request.title.isNotBlank()) { "업무 제목을 입력하세요." }
        jdbc.update("insert into tasks (id, title, team, owner_name, due_date, status) values (?, ?, ?, ?, cast(? as date), ?)", UUID.randomUUID().toString(), request.title.trim(), request.team.ifBlank { "운영" }, request.owner.ifBlank { "미지정" }, request.dueDate ?: LocalDate.now().plusDays(7).toString(), "진행 중")
        return snapshot()
    }

    @Synchronized fun changeTask(id: String, request: ChangeStatusRequest): OperationsData {
        require(jdbc.update("update tasks set status = ?, updated_at = now() where id = ?", request.status, id) == 1) { "업무를 찾을 수 없습니다." }
        return snapshot()
    }

    @Synchronized fun changeApproval(id: String, request: ChangeStatusRequest): OperationsData {
        val data = load(); val index = data.approvals.indexOfFirst { it.id == id }
        require(index >= 0) { "승인 요청을 찾을 수 없습니다." }
        data.approvals[index] = data.approvals[index].copy(status = request.status)
        return save(data)
    }


    @Synchronized fun deleteTask(id: String): OperationsData {
        require(jdbc.update("delete from tasks where id = ?", id) == 1) { "업무를 찾을 수 없습니다." }
        return snapshot()
    }
    @Synchronized fun recordRun(mode: String, result: AutomationResponse): AutomationRun {
        val data = load()
        val run = AutomationRun(UUID.randomUUID().toString(), mode, result.success, result.exitCode, OffsetDateTime.now().toString(), result.output.takeLast(1200))
        data.runs.add(0, run); while (data.runs.size > 20) data.runs.removeLast()
        save(data); return run
    }

    private fun load(): OperationsData {
        return documents.read("operations", OperationsData::class.java) ?: sample().also(::save)
    }
    private fun loadTasks(): List<TaskItem> = jdbc.query(
        "select id, title, team, owner_name, due_date, status from tasks order by due_date, created_at",
        { row, _ -> TaskItem(row.getString("id"), row.getString("title"), row.getString("team"), row.getString("owner_name"), row.getDate("due_date").toLocalDate().toString(), row.getString("status")) },
    )
    private fun save(data: OperationsData): OperationsData {
        documents.write("operations", data)
        return data
    }
    private fun sample() = OperationsData(
        isSample = true,
        kpi = Kpi(128_400_000, 79_800_000, 150_000_000, 12.4),
        tasks = mutableListOf(
            TaskItem("task-1", "9월 캠페인 성과 검토", "마케팅", "김지수", LocalDate.now().plusDays(2).toString(), "진행 중"),
            TaskItem("task-2", "고객 이탈 원인 정리", "CS", "이민호", LocalDate.now().plusDays(1).toString(), "지연"),
            TaskItem("task-3", "신규 입사자 온보딩", "운영", "박서연", LocalDate.now().plusDays(5).toString(), "대기")
        ),
        approvals = mutableListOf(
            ApprovalItem("approval-1", "비용", "광고 소재 제작비", "김지수", 1_200_000, "대기", LocalDate.now().toString()),
            ApprovalItem("approval-2", "휴가", "연차 휴가 (2일)", "박서연", null, "대기", LocalDate.now().minusDays(1).toString())
        )
    )
}

data class OperationsData(val isSample: Boolean = false, val kpi: Kpi = Kpi(), val tasks: MutableList<TaskItem> = mutableListOf(), val approvals: MutableList<ApprovalItem> = mutableListOf(), val runs: MutableList<AutomationRun> = mutableListOf())
data class Kpi(val revenue: Long = 0, val expense: Long = 0, val target: Long = 0, val weekChange: Double = 0.0)
data class TaskItem(val id: String, val title: String, val team: String, val owner: String, val dueDate: String, val status: String)
data class ApprovalItem(val id: String, val type: String, val title: String, val requester: String, val amount: Long?, val status: String, val requestedAt: String)
data class AutomationRun(val id: String, val mode: String, val success: Boolean, val exitCode: Int?, val executedAt: String, val output: String)
data class CreateTaskRequest(val title: String = "", val team: String = "", val owner: String = "", val dueDate: String? = null)
data class ChangeStatusRequest(val status: String)
