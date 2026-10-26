/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.data.security.SecretCrypto
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceTextPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshProbeResult
import me.rerere.workspace.ssh.SshShell
import me.rerere.workspace.ssh.SshWorkspaceBackend
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val sshPool: SshConnectionPool,
) {
    private val editLocks = ConcurrentHashMap<String, Mutex>()

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            if (workspace.isSsh()) continue
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                Log.w(TAG, "Workspace directory missing, removing record: id=${workspace.id}, root=${workspace.root}")
                dao.deleteById(workspace.id)
                cleanupAssistantReferences(workspace.id)
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        require(!workspace.isSsh()) { "SSH 工作区不需要安装本地环境" }
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        runInterruptible {
            remoteBackend(workspace)?.listFiles(area, path) ?: manager.listFiles(workspace.root, path, area)
        }
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        runInterruptible { remoteBackend(workspace)?.readText(path) ?: manager.readText(workspace.root, path) }
    }

    suspend fun readTextPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        maxBytes: Int,
    ): WorkspaceTextPreview = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        require(maxBytes in 1..512 * 1024) { "Invalid preview limit" }
        runInterruptible {
            remoteBackend(workspace)?.readTextPreview(area, path, maxBytes)
                ?: manager.readTextPreview(workspace.root, path, area, maxBytes)
        }
    }

    suspend fun writeText(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        runInterruptible {
            val remote = remoteBackend(workspace)
            if (area == WorkspaceStorageArea.FILES) {
                remote?.writeText(path, text, overwrite) ?: manager.writeText(workspace.root, path, text, overwrite)
            } else {
                require(remote != null) { "本地系统目录暂不支持在此编辑" }
                remote.writeAbsoluteText("/" + path.trimStart('/'), text, overwrite)
            }
        }
    }

    /** Detect changed/truncated/binary content before saving; SFTP itself offers no cross-client CAS. */
    suspend fun writeTextIfUnchanged(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        expectedText: String,
        text: String,
        expectedSshConfig: String? = null,
    ): WorkspaceFileEntry = editLocks.computeIfAbsent(id) { Mutex() }.withLock {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        check(expectedSshConfig == null || workspace.sshConfig == expectedSshConfig) {
            "SSH 连接配置已变化，请重新打开文件；当前编辑内容已保留。"
        }
        val remote = withContext(Dispatchers.IO) { remoteBackend(workspace) }
        require(remote != null) { "仅远程 SSH 文件支持在此编辑" }
        val current = runInterruptible(Dispatchers.IO) { remote.readTextPreview(area, path, 512 * 1024) }
        check(!current.isBinary && !current.truncated && current.text == expectedText) {
            "远程文件已变化或无法完整读取，请重新打开后再保存；当前编辑内容已保留。"
        }
        check(dao.getById(id)?.sshConfig == workspace.sshConfig) {
            "SSH 连接配置已变化，请重新打开文件；当前编辑内容已保留。"
        }
        // Keep this captured backend: a concurrent connection edit must never redirect the save to another host.
        runInterruptible(Dispatchers.IO) {
            if (area == WorkspaceStorageArea.FILES) remote.writeText(path, text, overwrite = true)
            else remote.writeAbsoluteText("/" + path.trimStart('/'), text, overwrite = true)
        }
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        runInterruptible {
            remoteBackend(workspace)?.writeText(path, text, overwrite)
                ?: manager.writeText(workspace.root, path, text, overwrite)
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        runInterruptible {
            remoteBackend(workspace)?.importFile(area, destinationPath, fileName, inputStream)
                ?: manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
        }
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        runInterruptible { remoteBackend(workspace)?.fileSize(area, path) ?: manager.fileSize(workspace.root, path, area) }
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val remote = remoteBackend(workspace)
        runInterruptible {
            if (remote != null) remote.exportFile(area, path, outputStream)
            else manager.exportFile(workspace.root, path, area, outputStream)
        }
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            runInterruptible {
                remoteBackend(workspace)?.deleteFile(area, path, recursive)
                    ?: manager.deleteFile(workspace.root, path, recursive, area)
            }
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        runInterruptible {
            remoteBackend(workspace)?.moveFile(source, target, overwrite)
                ?: manager.moveFile(workspace.root, source, target, overwrite)
        }
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            remoteBackend(workspace)?.executeCommand(command, cwd, timeoutMillis, stdin)
                ?: manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            sshPool.close(id)
            listOf("password", "privateKey", "passphrase").forEach { SecretCrypto.forget("workspace:$id:$it") }
            // Only local app data is removed; never delete a remote directory with a workspace entry.
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    suspend fun layout(id: String): WorkspaceLayout? = dao.getById(id)?.let {
        if (it.isSsh()) SshWorkspaceBackend.layoutOf(it.workspaceDir())
        else WorkspaceLayout("/workspace", listOf("/workspace", "/tmp", "/skills"))
    }

    suspend fun probeSsh(id: String?, draft: SshWorkspaceDraft): SshProbeResult {
        val previous = id?.let { dao.getById(it)?.sshSettings() }
        return runInterruptible(Dispatchers.IO) {
            sshPool.probe(draft.connectionConfig(id, previous))
        }
    }

    suspend fun saveSsh(id: String?, draft: SshWorkspaceDraft): WorkspaceEntity {
        val existing = id?.let { dao.getById(it) ?: error("工作区不存在") }
        require(existing == null || existing.isSsh()) { "不能将本地工作区改为 SSH 工作区" }
        val finalName = draft.name.trim().ifBlank { "SSH Workspace" }
        require(!isNameTaken(finalName, id)) { "工作区名称已存在" }
        require(draft.hostKey != null) { "请先确认服务器指纹" }
        val workspaceId = id ?: Uuid.random().toString()
        val config = draft.connectionConfig(id, existing?.sshSettings())
        val settings = withContext(Dispatchers.IO) {
            SshWorkspaceSettings(
                host = config.host, port = config.port, username = config.username, authType = config.authType,
                remoteDir = config.remoteDir, hostKey = config.hostKey,
                encryptedPassword = SecretCrypto.encrypt(config.password, "workspace:$workspaceId:password"),
                encryptedPrivateKey = SecretCrypto.encrypt(config.privateKey, "workspace:$workspaceId:privateKey"),
                encryptedPassphrase = SecretCrypto.encrypt(config.passphrase, "workspace:$workspaceId:passphrase"),
            )
        }
        val now = System.currentTimeMillis()
        val workspace = existing?.copy(name = finalName, sshConfig = JsonInstant.encodeToString(settings), updatedAt = now)
            ?: WorkspaceEntity(id = workspaceId, name = finalName, root = workspaceId,
                shellStatus = WorkspaceShellStatus.READY.name, createdAt = now, updatedAt = now,
                type = "SSH", sshConfig = JsonInstant.encodeToString(settings))
        dao.upsert(workspace)
        withContext(Dispatchers.IO) { sshPool.close(workspace.id) }
        return workspace
    }

    fun openSshShell(workspace: WorkspaceEntity, columns: Int, rows: Int): SshShell =
        requireNotNull(remoteBackend(workspace)).openShell(columns, rows)

    private fun remoteBackend(workspace: WorkspaceEntity): SshWorkspaceBackend? {
        if (!workspace.isSsh()) return null
        val settings = workspace.sshSettings() ?: error("SSH 工作区配置不可用")
        return SshWorkspaceBackend(sshPool, workspace.id, settings.connectionConfig(workspace.id))
    }

    private fun SshWorkspaceSettings.connectionConfig(id: String) = SshConnectionConfig(
        host = host, port = port, username = username, authType = authType, remoteDir = remoteDir, hostKey = hostKey,
        password = decryptSshSecret(encryptedPassword, "workspace:$id:password"),
        privateKey = decryptSshSecret(encryptedPrivateKey, "workspace:$id:privateKey"),
        passphrase = decryptSshSecret(encryptedPassphrase, "workspace:$id:passphrase"),
    )

    private fun SshWorkspaceDraft.connectionConfig(id: String?, previous: SshWorkspaceSettings?): SshConnectionConfig {
        require(host.isNotBlank() && username.isNotBlank() && port in 1..65535) { "请检查服务器、用户名和端口" }
        val dir = remoteDir.trim().trimEnd('/').ifEmpty { "/" }
        require(dir.startsWith('/') && !dir.contains('\u0000')) { "工作目录必须是有效绝对路径" }
        val saved = if (previous != null && id != null) runCatching { previous.connectionConfig(id) }.getOrNull() else null
        val secret = if (authType == SshAuthType.PASSWORD) password.ifEmpty { saved?.password.orEmpty() } else null
        val key = if (authType == SshAuthType.PRIVATE_KEY) privateKey.ifBlank { saved?.privateKey.orEmpty() } else null
        require(if (authType == SshAuthType.PASSWORD) !secret.isNullOrEmpty() else !key.isNullOrBlank()) { "请填写登录凭据" }
        return SshConnectionConfig(host.trim(), port, username.trim(), authType, secret, key,
            if (authType == SshAuthType.PRIVATE_KEY) passphrase.ifEmpty {
                if (privateKey.isBlank()) saved?.passphrase.orEmpty() else ""
            } else null, dir, hostKey)
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
    }
}

internal fun decryptSshSecret(value: String?, associatedData: String): String? {
    require(value.isNullOrEmpty() || SecretCrypto.isEncrypted(value)) { "SSH 登录凭据无效，请重新填写" }
    return SecretCrypto.decrypt(value, associatedData)
}
