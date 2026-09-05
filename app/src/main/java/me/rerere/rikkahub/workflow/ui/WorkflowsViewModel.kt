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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.ToolSurfaceBuilder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
    private val settingsStore: SettingsStore,
    private val toolSurfaceBuilder: ToolSurfaceBuilder,
) : ViewModel() {

    data class ToolCatalogItem(val name: String, val description: String, val schema: JsonObject?)

    suspend fun toolCatalog(assistantId: String?): List<ToolCatalogItem> {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: settings.assistants.firstOrNull { a ->
                a.localTools.any { it is me.rerere.rikkahub.data.ai.tools.LocalToolOption.Workflows }
            }
            ?: settings.assistants.firstOrNull()
            ?: return emptyList()
        return toolSurfaceBuilder.build(
            assistant = assistant,
            settings = settings,
            invocationContext = ToolInvocationContext(
                callerAssistantId = assistant.id.toString(), isHeadless = true,
            ),
        ).map { tool ->
            ToolCatalogItem(tool.name, tool.description, (tool.parameters() as? InputSchema.Obj)?.properties)
        }.filter { it.name != "workflow_run" }
    }

    val workflows: StateFlow<List<Loaded>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(id, enabled) }
    }

    fun update(definition: me.rerere.rikkahub.workflow.model.WorkflowDefinition, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val settings = settingsStore.settingsFlow.first()
                val author = definition.authoringAssistantId ?: settings.assistants.firstOrNull { assistant ->
                    assistant.localTools.any { it is me.rerere.rikkahub.data.ai.tools.LocalToolOption.Workflows }
                }?.id?.toString()
                repository.upsert(definition.copy(
                    authoringAssistantId = author,
                    updatedAtMs = System.currentTimeMillis(),
                ))
            } }
            onResult(result)
        }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteCascading(id) }
            onDone()
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome = engine.fire(id)

    suspend fun history(id: String, limit: Int = 20): List<WorkflowRun> =
        repository.lastRuns(id, limit)

    fun observe(id: String): Flow<Loaded?> = repository.observeById(id)

    fun observeRuns(id: String, limit: Int = 20): Flow<List<WorkflowRun>> = repository.observeRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)
}
