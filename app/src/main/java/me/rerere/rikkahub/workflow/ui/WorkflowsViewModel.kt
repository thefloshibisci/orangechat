/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
) : ViewModel() {

    val workflows: StateFlow<List<Loaded>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(id, enabled) }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCascading(id)
            onDone()
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome = engine.fire(id)

    suspend fun history(id: String, limit: Int = 20): List<WorkflowRun> =
        repository.lastRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)

    suspend fun save(definition: WorkflowDefinition) {
        repository.upsert(definition.copy(updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun duplicate(id: String): String? {
        val original = repository.getById(id)?.definition ?: return null
        val now = System.currentTimeMillis()
        val copy = original.copy(
            id = kotlin.uuid.Uuid.random().toString(),
            name = "${original.name} - 副本".take(80),
            enabled = false,
            createdAtMs = now,
            updatedAtMs = now,
        )
        repository.upsert(copy)
        return copy.id
    }

    suspend fun importJson(raw: String): Result<String> = runCatching {
        val parsed = WorkflowJson.parseStored(raw)
            ?: error("无法识别这个工作流 JSON")
        val now = System.currentTimeMillis()
        val imported = parsed.copy(
            id = kotlin.uuid.Uuid.random().toString(),
            name = "${parsed.name} - 导入".take(80),
            enabled = false,
            createdAtMs = now,
            updatedAtMs = now,
        )
        repository.upsert(imported)
        imported.id
    }

    suspend fun exportJson(id: String): String? =
        repository.getById(id)?.definition?.let(WorkflowJson::encode)
}
