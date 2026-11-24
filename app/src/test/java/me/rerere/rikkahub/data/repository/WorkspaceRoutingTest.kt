package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.room.Room
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.model.*
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.workspace.*
import me.rerere.workspace.ssh.SshConnectionPool
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class WorkspaceRoutingTest {
    @Test fun alreadyBuiltToolsUseLiveDefaultAndRejectRevokedBindings() = runBlocking { fixture { f ->
        val a = f.local("one"); val b = f.local("two")
        f.manager.writeText(a.root, "note", "one")
        f.manager.writeText(b.root, "note", "two")
        val owner = f.bind(a, b)
        val read = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_read_file" }
        val args = buildJsonObject { put("path", "/workspace/note") }
        assertTrue(read.execute(args).text().contains("one"))
        f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistants = listOf(owner.copy(workspaceId = Uuid.parse(b.id))))
        val changed = read.execute(args)
        assertTrue(changed.text().contains("two"))
        assertEquals(b.id, changed.first().metadata!!["workspace_id"]!!.jsonPrimitive.content)
        f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistants = listOf(owner.copy(workspaceId = Uuid.parse(b.id), workspaceIds = setOf(Uuid.parse(b.id)))))
        assertTrue(runCatching { read.execute(buildJsonObject { put("workspace_id", a.id); put("path", "/workspace/note") }) }.isFailure)
    } }

    @Test fun remoteCredentialFailureNeverExecutesLocalShellAndCarriesMachineIdentity() = runBlocking { fixture { f ->
        val local = f.local("phone")
        val remote = f.local("cloud").copy(type = "SSH", sshConfig = JsonInstant.encodeToString(
            SshWorkspaceSettings(host = "synthetic.invalid", username = "ubuntu", remoteDir = "/srv/chat")))
        f.db.workspaceDao().upsert(remote)
        val owner = f.bind(local, remote)
        val shell = createWorkspaceTools(local.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_shell" }
        f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistants = listOf(owner.copy(workspaceId = Uuid.parse(remote.id))))
        // Missing credentials fails before connecting. The local shell runner must never run.
        val error = runCatching { shell.execute(buildJsonObject { put("command", "echo sentinel") }) }.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains("cloud · ubuntu@synthetic.invalid:22"))
        val output = (error as WorkspaceToolExecutionException).output
        assertEquals("cloud · ubuntu@synthetic.invalid:22", (output.first() as UIMessagePart.Text).text)
        assertEquals(remote.id, output.first().metadata!!["workspace_id"]!!.jsonPrimitive.content)
        assertTrue(Json.parseToJsonElement((output.last() as UIMessagePart.Text).text).jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertEquals(0, f.shellCalls.size)
    } }

    @Test fun mistypedTargetReportsNotExecutedWithoutUsingDefault() = runBlocking { fixture { f ->
        val local = f.local("phone"); val owner = f.bind(local)
        val shell = createWorkspaceTools(local.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_shell" }
        val error = runCatching { shell.execute(buildJsonObject {
            put("workspace_id", "phoen"); put("command", "echo sentinel")
        }) }.exceptionOrNull() as WorkspaceToolExecutionException
        assertEquals("未执行 · 请求工作区：phoen", (error.output.first() as UIMessagePart.Text).text)
        assertFalse(error.output.first().metadata!!.containsKey("workspace_id"))
        assertTrue(f.shellCalls.isEmpty())
    } }

    @Test fun workspaceTargetIsOptionalButTransferEndpointsStayRequired() = runBlocking { fixture { f ->
        val local = f.local("phone"); val owner = f.bind(local)
        val tools = createWorkspaceTools(local.id, f.repo, assistantId = owner.id.toString())
        for (tool in tools.filterNot { it.name == "workspace_transfer_file" }) {
            val schema = tool.parameters() as InputSchema.Obj
            assertTrue(schema.properties.containsKey("workspace_id"))
            assertFalse(schema.required.orEmpty().contains("workspace_id"))
        }
        val transferSchema = tools.first { it.name == "workspace_transfer_file" }.parameters() as InputSchema.Obj
        assertTrue(transferSchema.required.orEmpty().containsAll(listOf("source_workspace_id", "destination_workspace_id")))
    } }

    @Test fun stricterApprovalChangedDuringModelCallFailsClosed() = runBlocking { fixture { f ->
        val a = f.local("local"); val owner = f.bind(a)
        val write = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_write_file" }
        assertFalse(write.needsApproval)
        f.repo.setToolApproval(a.id, "workspace_write_file", true)
        assertTrue(runCatching { write.execute(buildJsonObject { put("path", "/workspace/no"); put("text", "no") }) }.isFailure)
        assertFalse(File(f.manager.filesDir(a.root), "no").exists())
    } }

    @Test fun shellFailureIsRecordedAndStopsWorkflowWhileResultsRetainMachineLabel() = runBlocking { fixture { f ->
        val a = f.local("phone")
        val owner = f.bind(a)
        f.shellResult = WorkspaceCommandResult(2, "", "mail.py not found")
        val args = buildJsonObject { put("workspace_id", a.id); put("command", "python mail.py") }
        val tools = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString())
        val output = tools.first { it.name == "workspace_shell" }.execute(args)
        assertTrue(output.text().startsWith("phone · 本地 Android"))
        val outcome = WorkflowActionRunner().run(listOf(WorkflowAction("workspace_shell", args),
            WorkflowAction("workspace_write_file", buildJsonObject { put("workspace_id", a.id); put("path", "/workspace/later"); put("text", "bad") })), tools)
        assertFalse(outcome.success)
        assertEquals(listOf("FAILED", "SKIPPED"), outcome.steps.map { it.status })
        assertFalse(File(f.manager.filesDir(a.root), "later").exists())
    } }

    @Test fun editKeepsDiffAndWorkspaceMetadataTogether() = runBlocking { fixture { f ->
        val a = f.local("phone"); val owner = f.bind(a)
        f.manager.writeText(a.root, "note", "old\n")
        val edit = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_edit_file" }
        val output = edit.execute(buildJsonObject { put("workspace_id", a.id); put("path", "/workspace/note"); put("old_text", "old"); put("new_text", "new") })
        val result = output.filterIsInstance<UIMessagePart.Text>().first { it.metadata?.containsKey("diff") == true }
        assertEquals(a.id, result.metadata!!["workspace_id"]!!.jsonPrimitive.content)
        assertEquals("/workspace", result.metadata!!["workspace_root"]!!.jsonPrimitive.content)
        assertEquals("new\n", f.manager.readText(a.root, "note"))
    } }

    @Test fun binaryTransferWorksBothWaysAndPreservesExistingDestinationByDefault() = runBlocking { fixture { f ->
        val a = f.local("source"); val b = f.local("destination"); val owner = f.bind(a, b)
        val bytes = byteArrayOf(0, 1, -1, 0, 127)
        File(f.manager.filesDir(a.root), "binary").writeBytes(bytes)
        val transfer = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_transfer_file" }
        fun args(from: WorkspaceEntity, to: WorkspaceEntity, source: String, dest: String, overwrite: Boolean = false) = buildJsonObject {
            put("source_workspace_id", from.id); put("destination_workspace_id", to.id)
            put("source_path", "/workspace/$source"); put("destination_path", "/workspace/$dest"); put("overwrite", overwrite)
        }
        transfer.execute(args(a, b, "binary", "nested/binary"))
        assertArrayEquals(bytes, File(f.manager.filesDir(b.root), "nested/binary").readBytes())
        File(f.manager.filesDir(a.root), "binary").writeText("changed")
        assertTrue(runCatching { transfer.execute(args(a, b, "binary", "nested/binary")) }.isFailure)
        assertArrayEquals(bytes, File(f.manager.filesDir(b.root), "nested/binary").readBytes())
        transfer.execute(args(b, a, "nested/binary", "back"))
        assertArrayEquals(bytes, File(f.manager.filesDir(a.root), "back").readBytes())
        transfer.execute(args(a, b, "binary", "nested/binary", overwrite = true))
        assertEquals("changed", File(f.manager.filesDir(b.root), "nested/binary").readText())
    } }

    @Test fun oversizedTransferRejectsBeforeCreatingDestination() = runBlocking { fixture { f ->
        val a = f.local("a"); val b = f.local("b"); val owner = f.bind(a, b)
        RandomAccessFile(File(f.manager.filesDir(a.root), "huge.apk"), "rw").use { it.setLength(WORKSPACE_TRANSFER_MAX_BYTES + 1) }
        val transfer = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_transfer_file" }
        val failure = runCatching { transfer.execute(buildJsonObject {
            put("source_workspace_id", a.id); put("destination_workspace_id", b.id)
            put("source_path", "/workspace/huge.apk"); put("destination_path", "/workspace/huge.apk")
        }) }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("20 MiB"))
        assertFalse(File(f.manager.filesDir(b.root), "huge.apk").exists())
    } }

    @Test fun lexicalAndCanonicalEscapesAreRejectedBeforeWrite() = runBlocking { fixture { f ->
        val a = f.local("a"); val owner = f.bind(a)
        val write = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_write_file" }
        for (path in listOf("/etc/passwd", "/workspace-other/file", "/workspace/../file")) {
            assertTrue(runCatching { write.execute(buildJsonObject { put("path", path); put("text", "bad") }) }.isFailure)
        }
        val outside = File(f.base, "outside").apply { mkdirs() }
        val link = File(f.manager.filesDir(a.root), "escape")
        if (System.getProperty("os.name").startsWith("Windows")) {
            // A directory junction exercises canonical-path escape without requiring symlink privileges.
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, outside.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        } else java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(runCatching { write.execute(buildJsonObject { put("path", "/workspace/escape/file"); put("text", "bad") }) }.isFailure)
        assertFalse(File(outside, "file").exists())
        val shell = createWorkspaceTools(a.id, f.repo, assistantId = owner.id.toString()).first { it.name == "workspace_shell" }
        assertTrue(runCatching { shell.execute(buildJsonObject { put("cwd", "escape"); put("command", "echo unsafe") }) }.isFailure)
        assertTrue(f.shellCalls.isEmpty())
    } }

    @Test fun workflowDefaultStaysUnspecifiedAndFollowsLiveAssistant() = runBlocking { fixture { f ->
        val local = f.local("phone"); val other = f.local("another"); val owner = f.bind(local, other)
        val workflows = WorkflowRepository(f.db.workflowDao(), f.db.workflowRunDao())
        val definition = WorkflowDefinition("postal", "邮局", trigger = TriggerSpec.TimeCron(timeOfDay = "08:00"), actions = listOf(
            WorkflowAction("workspace_shell", buildJsonObject { put("command", "python mail.py") })))
        workflows.upsert(definition)
        val stored = f.db.workflowDao().getById("postal")!!.definitionJson
        val firstRead = me.rerere.rikkahub.workflow.tools.workflowGetTool(workflows).execute(buildJsonObject { put("id", "postal") })
            .filterIsInstance<UIMessagePart.Text>().single().text
        val visibleDefinition = Json.parseToJsonElement(firstRead).jsonObject["definition"]!!.jsonObject
        assertFalse(visibleDefinition["actions"]!!.jsonArray.single().jsonObject["args"]!!.jsonObject.containsKey("workspace_id"))
        val loaded = workflows.getById("postal")!!.definition
        assertEquals(stored, f.db.workflowDao().getById("postal")!!.definitionJson)
        val tools = createWorkspaceTools(local.id, f.repo, assistantId = owner.id.toString())
        val result = WorkflowActionRunner().run(loaded.actions, tools)
        assertTrue(result.error, result.success)
        assertEquals(listOf(local.root), f.shellCalls)
        f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(assistants = listOf(owner.copy(workspaceId = Uuid.parse(other.id))))
        val changed = WorkflowActionRunner().run(loaded.actions, tools)
        assertTrue(changed.error, changed.success)
        assertEquals(listOf(local.root, other.root), f.shellCalls)
        assertEquals(stored, f.db.workflowDao().getById("postal")!!.definitionJson)
    } }

    @Test fun workflowWithRemoteDefaultIsNeverMigratedToAvailableLocalWorkspace() = runBlocking { fixture { f ->
        val local = f.local("phone")
        val remote = f.local("cloud").copy(type = "SSH", sshConfig = JsonInstant.encodeToString(
            SshWorkspaceSettings(host = "synthetic.invalid", username = "ubuntu", remoteDir = "/srv/chat")))
        f.db.workspaceDao().upsert(remote)
        val owner = f.bind(remote, local)
        val workflows = WorkflowRepository(f.db.workflowDao(), f.db.workflowRunDao())
        val definition = WorkflowDefinition("remote-task", "remote", trigger = TriggerSpec.Manual, actions = listOf(
            WorkflowAction("workspace_shell", buildJsonObject { put("command", "echo sentinel") })))
        workflows.upsert(definition)
        val stored = f.db.workflowDao().getById(definition.id)!!.definitionJson
        val loaded = workflows.getById(definition.id)!!.definition
        val tools = createWorkspaceTools(remote.id, f.repo, assistantId = owner.id.toString())
        val outcome = WorkflowActionRunner().run(loaded.actions, tools)
        assertFalse(outcome.success)
        assertTrue(outcome.error.orEmpty().contains("ubuntu@synthetic.invalid:22"))
        assertTrue(f.shellCalls.isEmpty())
        assertEquals(stored, f.db.workflowDao().getById(definition.id)!!.definitionJson)
        assertFalse(loaded.actions.single().args.containsKey("workspace_id"))
    } }

    @Test fun callSnapshotCannotSwitchMachineDuringIOButNextCallSeesNewConfig() = runBlocking { fixture { f ->
        val a = f.local("first")
        f.repo.withToolTargets(listOf(a)) {
            f.db.workspaceDao().upsert(a.copy(name = "changed", type = "SSH", sshConfig = "invalid"))
            assertEquals("first", f.repo.getById(a.id)!!.name)
            f.repo.writeToolBytes(a.id, "/workspace/test", byteArrayOf(1), false)
        }
        assertTrue(f.repo.getById(a.id)!!.isSsh())
        assertTrue(runCatching { f.repo.writeToolBytes(a.id, "/workspace/test", byteArrayOf(2), true) }.isFailure)
        assertArrayEquals(byteArrayOf(1), File(f.manager.filesDir(a.root), "test").readBytes())
    } }

    private fun List<UIMessagePart>.text() = filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private class Fixture(val db: AppDatabase, val repo: WorkspaceRepository, val manager: WorkspaceManager,
        val settings: SettingsStore, val base: File, val shellCalls: MutableList<String>) {
        var shellResult = WorkspaceCommandResult(0, "synthetic shell", "")
        suspend fun local(name: String): WorkspaceEntity {
            val row = repo.create(name).copy(shellStatus = WorkspaceShellStatus.READY.name)
            db.workspaceDao().upsert(row)
            return row
        }
        fun bind(vararg workspaces: WorkspaceEntity): Assistant {
            val owner = Assistant(name = workspaces.first().name, workspaceId = Uuid.parse(workspaces.first().id), workspaceIds = workspaces.map { Uuid.parse(it.id) }.toSet())
            // Publish directly in this fixture: production SettingsStore publishes this same live flow before persistence.
            settings.settingsFlow.value = settings.settingsFlow.value.copy(assistants = listOf(owner))
            return owner
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val scope = AppScope().also { it.cancel() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val base = File(context.cacheDir, "workspace-routing-${Uuid.random()}")
        val calls = mutableListOf<String>()
        lateinit var fixture: Fixture
        val manager = WorkspaceManager(base, shellRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                calls += context.root
                return fixture.shellResult
            }
        })
        val pool = SshConnectionPool()
        try {
            val settings = SettingsStore(context, scope)
            fixture = Fixture(db, WorkspaceRepository(db.workspaceDao(), manager, RootfsInstaller(manager), settings, pool), manager, settings, base, calls)
            block(fixture)
        } finally { pool.close(); db.close(); base.deleteRecursively(); scope.cancel(); stopKoin() }
    }
}
