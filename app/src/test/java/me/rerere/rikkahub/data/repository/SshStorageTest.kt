package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.room.Room
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.SshWorkspaceDraft
import me.rerere.rikkahub.data.security.SecretCrypto
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import me.rerere.workspace.*
import me.rerere.workspace.ssh.*
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
import java.security.Security
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SshStorageTest {
    @Test fun secretsStayEncrypted() = runBlocking { fixture { db, repo, _ ->
        val saved = repo.saveSsh(null, draft())
        val reloaded = db.workspaceDao().getById(saved.id)!!
        assertTrue(reloaded.isSsh())
        val settings = reloaded.sshSettings()!!
        assertFalse(reloaded.sshConfig!!.contains("synthetic-password"))
        assertTrue(SecretCrypto.isEncrypted(settings.encryptedPassword))
        SecretCrypto.forget("workspace:${saved.id}:password")
        assertEquals("synthetic-password", decryptSshSecret(settings.encryptedPassword, "workspace:${saved.id}:password"))
        assertTrue(runCatching { decryptSshSecret(settings.encryptedPassword, "workspace:other:password") }.isFailure)
        val edited = repo.saveSsh(saved.id, draft().copy(name = "renamed", password = ""))
        assertEquals(saved.id, edited.id)
        assertEquals("renamed", edited.name)
        assertEquals("synthetic-password", decryptSshSecret(edited.sshSettings()!!.encryptedPassword, "workspace:${saved.id}:password"))
        assertFalse(draft().toString().contains("synthetic-password"))
    } }

    @Test fun privateKeyAndPassphrase() = runBlocking { fixture { _, repo, _ ->
        val saved = repo.saveSsh(null, draft().copy(authType = SshAuthType.PRIVATE_KEY,
            privateKey = "synthetic-private-key", passphrase = "synthetic-passphrase"))
        val settings = saved.sshSettings()!!
        assertNull(settings.encryptedPassword)
        assertFalse(saved.sshConfig!!.contains("synthetic-private-key"))
        assertFalse(saved.sshConfig!!.contains("synthetic-passphrase"))
        assertEquals("synthetic-private-key", decryptSshSecret(settings.encryptedPrivateKey, "workspace:${saved.id}:privateKey"))
        assertEquals("synthetic-passphrase", decryptSshSecret(settings.encryptedPassphrase, "workspace:${saved.id}:passphrase"))
    } }

    @Test fun invalidRemoteNeverUsesLocal() = runBlocking { fixture { db, repo, manager ->
        val bad = WorkspaceEntity("broken", "broken", "broken", createdAt = 1, updatedAt = 1, type = "SSH", sshConfig = "{invalid}")
        db.workspaceDao().upsert(bad)
        manager.ensureWorkspace(bad.root)
        manager.writeText(bad.root, "sentinel", "local must not be read", true)
        assertTrue(runCatching { repo.readText(bad.id, "sentinel") }.isFailure)
        assertTrue(runCatching { repo.writeText(bad.id, "sentinel", "wrong target", true) }.isFailure)
        assertEquals("local must not be read", manager.readText(bad.root, "sentinel"))
        assertTrue(runCatching { decryptSshSecret("plaintext", "workspace:broken:password") }.isFailure)
    } }

    @Test fun remoteSurvivesIntegrityCheck() = runBlocking { fixture { db, repo, manager ->
        val saved = repo.saveSsh(null, draft())
        assertFalse(manager.workspaceDir(saved.root).exists())
        repo.checkIntegrity()
        assertEquals(saved, db.workspaceDao().getById(saved.id))
        assertTrue(runCatching { repo.installRootfs(saved.id, "https://synthetic.invalid/no-network") }.isFailure)
        assertTrue(runCatching { repo.saveSsh(null, draft().copy(name = "untrusted", hostKey = null)) }.isFailure)
        assertEquals(1, db.workspaceDao().getAll().size)
        db.workspaceDao().upsert(saved.copy(sshConfig = "changed connection"))
        val mismatch = runCatching {
            repo.writeTextIfUnchanged(saved.id, WorkspaceStorageArea.FILES, "file", "old", "edited",
                expectedSshConfig = saved.sshConfig)
        }.exceptionOrNull()
        assertTrue(mismatch!!.message.orEmpty().contains("连接配置已变化"))
    } }

    private fun draft() = SshWorkspaceDraft(name = "remote", host = "synthetic.invalid", username = "user",
        password = "synthetic-password", remoteDir = "/srv/chat", hostKey = SshHostKey("ssh-ed25519", "AQID"))

    private suspend fun fixture(block: suspend (AppDatabase, WorkspaceRepository, WorkspaceManager) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = TestAndroidKeyStore.TestProvider()
        Security.addProvider(provider)
        startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
        val scope = AppScope().also { it.cancel() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val base = File(context.cacheDir, "ws-${Uuid.random()}")
        val manager = WorkspaceManager(base)
        val pool = SshConnectionPool()
        try {
            val settings = SettingsStore(context, scope)
            block(db, WorkspaceRepository(db.workspaceDao(), manager, RootfsInstaller(manager), settings, pool), manager)
        } finally {
            pool.close(); db.close(); base.deleteRecursively(); scope.cancel()
            stopKoin(); Security.removeProvider(provider.name)
        }
    }
}
