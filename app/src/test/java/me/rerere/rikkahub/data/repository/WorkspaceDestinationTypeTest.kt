package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.room.Room
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
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
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class WorkspaceDestinationTypeTest {
    @Test
    fun overwriteRejectsEmptyDirectoryAndPreservesIt() = runBlocking {
        fixture { repo, manager ->
            val workspace = repo.create("phone")
            val directory = File(manager.filesDir(workspace.root), "letters").apply { mkdirs() }

            val failure = runCatching {
                repo.writeToolBytes(workspace.id, "/workspace/letters", byteArrayOf(1), overwrite = true)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(directory.isDirectory)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun overwriteStillReplacesRegularFile() = runBlocking {
        fixture { repo, manager ->
            val workspace = repo.create("phone")
            val file = File(manager.filesDir(workspace.root), "letter").apply { writeText("old") }
            val bytes = byteArrayOf(0, 1, -1)

            val entry = repo.writeToolBytes(workspace.id, "/workspace/letter", bytes, overwrite = true)

            assertTrue(file.isFile)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), entry.sizeBytes)
        }
    }

    private suspend fun fixture(block: suspend (WorkspaceRepository, WorkspaceManager) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val scope = AppScope().also { it.cancel() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val base = File(context.cacheDir, "workspace-destination-${Uuid.random()}")
        val manager = WorkspaceManager(base)
        val pool = SshConnectionPool()
        try {
            val repo = WorkspaceRepository(
                db.workspaceDao(), manager, RootfsInstaller(manager), SettingsStore(context, scope), pool,
            )
            block(repo, manager)
        } finally {
            pool.close()
            db.close()
            base.deleteRecursively()
            scope.cancel()
            stopKoin()
        }
    }
}
