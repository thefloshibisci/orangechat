package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.boundWorkspaceIds
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream

const val WORKSPACE_TRANSFER_MAX_BYTES = 20L * 1024 * 1024

internal class WorkspaceToolExecutionException(
    val output: List<UIMessagePart>,
    message: String,
    cause: Exception,
) : IllegalStateException(message, cause)

fun WorkspaceEntity.machineLabel(): String = if (isSsh()) {
    val config = sshSettings()
    if (config == null) "$name · SSH（配置不可用）" else "$name · ${config.username}@${config.host}:${config.port}"
} else "$name · 本地 Android"

/** Refreshes permissions and the default at every execute, including calls resumed after approval. */
suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    assistantId: String? = null,
): List<Tool> {
    val repo = workspaceRepository
    fun liveBinding() = assistantId?.let { id ->
        checkNotNull(repo.currentAssistant(id)) { "Assistant no longer exists: $id" }
    }
    suspend fun resolve(requested: String?): WorkspaceEntity {
        val assistant = liveBinding()
        val allowed = assistant?.boundWorkspaceIds()?.map { it.toString() }?.toSet()
        val id = requested ?: assistant?.workspaceId?.toString() ?: workspaceId.takeIf { assistantId == null }
            ?: error("No default workspace; specify workspace_id")
        require(allowed == null || id in allowed) { "Workspace is not currently bound to this assistant: $id" }
        return checkNotNull(repo.getById(id)) { "Workspace not found: $id; no fallback" }
    }
    if (assistantId != null && liveBinding()?.boundWorkspaceIds().isNullOrEmpty()) return emptyList()
    val initialIds = liveBinding()?.boundWorkspaceIds()?.map { it.toString() }.orEmpty()
        .ifEmpty { listOfNotNull(workspaceId) }
    val initial = initialIds.mapNotNull { repo.getById(it) }
    if (assistantId == null && workspaceId == null) return emptyList()
    val templates = createFixedWorkspaceTools(workspaceId ?: initialIds.firstOrNull() ?: "unbound", repo, null)
    val routed = templates.map { template ->
        // Conservatively aggregate the currently bound permissions. A stricter permission changed
        // while a model/approval is pending fails closed and asks for a fresh call below.
        val approval = initial.any { resolveWorkspaceToolApproval(template.name, it.toolApprovalOverrides()) } ||
            (initial.isEmpty() && WorkspaceToolDefaultApprovals[template.name] == true)
        template.copy(
            description = template.description + " Select workspace_id from the workspace list. Paths outside its root are rejected. Never retry a remote error locally.",
            parameters = {
                val schema = template.parameters() as InputSchema.Obj
                schema.copy(properties = JsonObject(schema.properties + ("workspace_id" to buildJsonObject {
                    put("type", "string"); put("description", "Optional exact workspace ID from system instructions. Omit to use the assistant's current default workspace.")
                })))
            },
            needsApproval = approval,
            execute = { input ->
                var target: WorkspaceEntity? = null
                try {
                    val args = input.jsonObject
                    val workspace = resolve(args["workspace_id"]?.jsonPrimitive?.contentOrNull).also { target = it }
                    check(workspace.shellStatus == WorkspaceShellStatus.READY.name) { "Workspace is not ready; no fallback" }
                    check(approval || !resolveWorkspaceToolApproval(template.name, workspace.toolApprovalOverrides())) {
                        "Workspace approval settings changed; submit a new tool call for confirmation"
                    }
                    val fixed = createFixedWorkspaceTools(workspace.id, repo, cwd.takeIf { assistantId == null && workspace.id == workspaceId }).first { it.name == template.name }
                    val result = repo.withToolTargets(listOf(workspace)) { fixed.execute(input) }
                    labelResult(workspace, result)
                } catch (c: CancellationException) { throw c }
                catch (e: Exception) {
                    throw workspaceFailure(target, target?.machineLabel() ?: "未执行 · 请求工作区：${requestedTarget(input, "workspace_id")}", e)
                }
            },
        )
    }
    val transferApproval = initial.isEmpty() || initial.any {
        resolveWorkspaceToolApproval("workspace_transfer_file", it.toolApprovalOverrides())
    }
    val transfer = Tool(
        name = "workspace_transfer_file",
        description = "Copy one binary/text file between two explicitly selected workspaces, in either direction. Maximum 20 MiB; default no overwrite. Both paths must be inside their roots. Remote failure never falls back locally.",
        needsApproval = transferApproval,
        parameters = { InputSchema.Obj(properties = buildJsonObject {
            for (key in listOf("source_workspace_id", "destination_workspace_id", "source_path", "destination_path")) {
                put(key, buildJsonObject { put("type", "string") })
            }
            put("overwrite", buildJsonObject { put("type", "boolean"); put("description", "Defaults to false") })
        }, required = listOf("source_workspace_id", "destination_workspace_id", "source_path", "destination_path")) },
        execute = { input ->
            var sourceTarget: WorkspaceEntity? = null
            var destinationTarget: WorkspaceEntity? = null
            try {
                val args = input.jsonObject
                fun required(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("$key is required")
                val source = resolve(required("source_workspace_id")).also { sourceTarget = it }
                val destination = resolve(required("destination_workspace_id")).also { destinationTarget = it }
                val label = "${source.machineLabel()} → ${destination.machineLabel()}"
                require(source.id != destination.id) { "Choose two different workspaces" }
                check(listOf(source, destination).all { it.shellStatus == WorkspaceShellStatus.READY.name }) { "Workspace not ready" }
                check(transferApproval || listOf(source, destination).none {
                    resolveWorkspaceToolApproval("workspace_transfer_file", it.toolApprovalOverrides())
                }) { "Workspace approval settings changed; submit a new tool call for confirmation" }
                repo.withToolTargets(listOf(source, destination)) {
                    val from = repo.validateToolPath(source.id, required("source_path"))
                    val to = repo.validateToolPath(destination.id, required("destination_path"))
                    val relative = from.removePrefix(source.workspaceDir().trimEnd('/')).trimStart('/')
                    val size = repo.fileSize(source.id, WorkspaceStorageArea.FILES, relative)
                    require(size in 0..WORKSPACE_TRANSFER_MAX_BYTES) { "Transfer exceeds 20 MiB limit ($size bytes); nothing downloaded" }
                    val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
                    // Check existence before spending mobile data. Publish rechecks to cover concurrent writers.
                    if (!overwrite) {
                        require(!repo.toolFileExists(destination.id, to)) { "File already exists: $to" }
                    }
                    val buffer = ByteArrayOutputStream()
                    repo.exportFile(source.id, WorkspaceStorageArea.FILES, relative, boundedWorkspaceOutput(buffer, WORKSPACE_TRANSFER_MAX_BYTES))
                    val entry = repo.writeToolBytes(destination.id, to, buffer.toByteArray(), overwrite)
                    labelResult(destination, listOf(UIMessagePart.Text(buildJsonObject {
                        put("source_workspace_id", source.id); put("destination_workspace_id", destination.id)
                        put("path", to); put("sizeBytes", entry.sizeBytes); put("overwrite", overwrite)
                    }.toString())), label)
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                val from = sourceTarget?.machineLabel() ?: requestedTarget(input, "source_workspace_id")
                val to = destinationTarget?.machineLabel() ?: requestedTarget(input, "destination_workspace_id")
                val label = if (sourceTarget != null && destinationTarget != null) "$from → $to" else "未执行 · 请求传输：$from → $to"
                throw workspaceFailure(destinationTarget, label, e)
            }
        },
    )
    return routed + transfer
}

private fun requestedTarget(input: JsonElement, key: String): String =
    ((input as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull ?: if (key == "workspace_id") "默认工作区" else "未指定"

private fun workspaceFailure(workspace: WorkspaceEntity?, label: String, error: Exception): WorkspaceToolExecutionException {
    val reason = error.message ?: error.javaClass.simpleName
    val output = labelResult(workspace, listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("isError", true)
        put("error", reason)
    }.toString())), label)
    return WorkspaceToolExecutionException(output, "$label\n$reason", error)
}

private fun labelResult(workspace: WorkspaceEntity?, parts: List<UIMessagePart>, label: String = workspace?.machineLabel() ?: "未执行"): List<UIMessagePart> {
    val metadata = buildJsonObject {
        workspace?.let { put("workspace_id", it.id); put("workspace_root", it.workspaceDir()) }
        put("machine", label)
    }
    // Keep JSON payloads valid (workflow error detection) and retain images/diffs.
    return listOf(UIMessagePart.Text(label).apply { this.metadata = metadata }) + parts.map { part ->
        if (part is UIMessagePart.Text) part.apply { this.metadata = JsonObject(this.metadata.orEmpty() + metadata) } else part
    }
}
