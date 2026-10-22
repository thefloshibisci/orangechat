package me.rerere.rikkahub

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.data.sync.ChatHistoryArchive
import me.rerere.rikkahub.data.sync.StartupRecoveryState
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.uuid.Uuid
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Exercise the actual Application/DI startup rather than only the recovery coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(application = RikkaHubApp::class, sdk = [28], shadows = [HostQuickJsLoader::class])
class ApplicationStartupTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test
    @Config(shadows = [HostQuickJsLoader::class, AutomaticRestoreApplication::class])
    fun `new task restores after progress screen without a second confirmation`() {
        val app = RuntimeEnvironment.getApplication() as RikkaHubApp
        val recovery = requireNotNull(app.startupRecovery)
        org.junit.Assert.assertEquals(StartupRecoveryState.ReadyToRestore, recovery.state.value)
        org.junit.Assert.assertFalse(PendingRestoreApplication.databaseCreated)
        val controller = Robolectric.buildActivity(RouteActivity::class.java).setup().visible()
        try {
            // Drive real Compose frames, without clicking Continue or calling coordinator.retry.
            compose.waitForIdle()
            pumpMainUntil { recovery.isReady || recovery.state.value is StartupRecoveryState.Failed }
            assertTrue("Automatic restore state: ${recovery.state.value}", recovery.isReady)
            val database = GlobalContext.get().get<AppDatabase>()
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_node").use {
                assertTrue(it.moveToFirst()); org.junit.Assert.assertEquals(1000, it.getInt(0))
            }
            org.junit.Assert.assertFalse(me.rerere.rikkahub.data.sync.ChatHistoryBackup.hasPending(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(shadows = [HostQuickJsLoader::class, PendingRestoreApplication::class])
    fun `invalid staged archive displays failure and still allows entry by cancellation`() {
        val app = RuntimeEnvironment.getApplication() as RikkaHubApp
        val recovery = requireNotNull(app.startupRecovery)
        File(app.noBackupFilesDir, "chat-history-restore/${PendingRestoreApplication.planId}/chats.json")
            .writeText("invalid archive")
        val controller = Robolectric.buildActivity(RouteActivity::class.java).setup().visible()
        try {
            compose.onNodeWithText("继续恢复聊天记录").performScrollTo().assertIsDisplayed().performClick()
            pumpMainUntil { recovery.state.value is StartupRecoveryState.Failed }
            assertTrue("Recovery state: ${recovery.state.value}", recovery.state.value is StartupRecoveryState.Failed)
            compose.onNodeWithText("恢复未完成：", substring = true).assertIsDisplayed()
            compose.onNodeWithText("取消恢复并进入应用").performClick()
            pumpMainUntil { recovery.isReady }
            assertTrue("Cancel state: ${recovery.state.value}", recovery.isReady)
        } finally { controller.pause().stop().destroy() }
    }

    private fun pumpMainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }

    @Test
    @Config(shadows = [HostQuickJsLoader::class, PendingRestoreApplication::class])
    fun `pending restore cancellation opens app even when pending JSON is corrupt`() {
        val app = RuntimeEnvironment.getApplication() as RikkaHubApp
        val recovery = requireNotNull(app.startupRecovery)
        val pending = File(app.noBackupFilesDir, "chat-history-restore/pending.json")
        pending.writeText("broken pointer")
        org.junit.Assert.assertEquals(StartupRecoveryState.AwaitingConfirmation, recovery.state.value)
        org.junit.Assert.assertFalse(PendingRestoreApplication.databaseCreated)
        val controller = Robolectric.buildActivity(RouteActivity::class.java).setup().visible()
        try {
            compose.onNodeWithText("继续恢复聊天记录").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("取消恢复并进入应用").performScrollTo().assertIsDisplayed().performClick()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!recovery.isReady && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Cancel state: ${recovery.state.value}", recovery.isReady)
            org.junit.Assert.assertFalse(pending.exists())
            assertTrue(File(pending.parentFile, PendingRestoreApplication.planId + "/chats.json").isFile)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(shadows = [HostQuickJsLoader::class, PendingRestoreApplication::class])
    fun `pending backup reaches Activity and completes through actual Application wiring`() {
        val app = RuntimeEnvironment.getApplication() as RikkaHubApp
        val recovery = requireNotNull(app.startupRecovery)
        val controller = Robolectric.buildActivity(RouteActivity::class.java).setup().visible()
        try {
            org.junit.Assert.assertEquals(StartupRecoveryState.AwaitingConfirmation, recovery.state.value)
            compose.onNodeWithText("继续恢复聊天记录").performScrollTo().assertIsDisplayed().performClick()
            val deadline = System.nanoTime() + 30_000_000_000L
            var heartbeats = 0
            while (!recovery.isReady && recovery.state.value !is StartupRecoveryState.Failed && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                heartbeats++
                Thread.yield()
            }
            assertTrue("Recovery state: ${recovery.state.value}; main heartbeats=$heartbeats", recovery.isReady)
            assertTrue("Main looper must run while recovery is pending", heartbeats > 0)
            assertTrue(!File(app.noBackupFilesDir, "chat-history-restore/pending.json").exists())
            val database = GlobalContext.get().get<AppDatabase>()
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_node").use {
                assertTrue(it.moveToFirst()); org.junit.Assert.assertEquals(1000, it.getInt(0))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `fresh Application reaches its startup coordinator`() {
        assertNotNull((RuntimeEnvironment.getApplication() as RikkaHubApp).startupRecovery)
        val controller = Robolectric.buildActivity(RouteActivity::class.java).setup()
        try {
            val store = GlobalContext.get().get<SettingsStore>()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (store.settingsFlow.value.init && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Settings must leave the dummy state", !store.settingsFlow.value.init)
            assertTrue((RuntimeEnvironment.getApplication() as RikkaHubApp).startupRecovery!!.isReady)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(controller.get().window.decorView)
        } finally { controller.pause().stop().destroy() }
    }
}

/** Seed the on-disk handoff BEFORE real Application.onCreate, not after startup has finished. */
@Implements(RikkaHubApp::class)
open class PendingRestoreApplication : org.robolectric.shadows.ShadowApplication() {
    protected open val automatic = false
    companion object {
        @Volatile var databaseCreated = false
        var planId = ""
    }
    @RealObject private lateinit var app: RikkaHubApp

    @Implementation fun onCreate() {
        val plan = Uuid.random()
        planId = plan.toString()
        databaseCreated = false
        val assistant = Uuid.random()
        val directory = File(app.noBackupFilesDir, "chat-history-restore/$plan").apply { mkdirs() }
        val conversation = Conversation(assistantId = assistant, title = "startup backup",
            messageNodes = (0 until 1000).map { UIMessage.user("startup message $it " + "text ".repeat(100)).toMessageNode() })
        File(directory, "chats.json").writeText(JsonInstant.encodeToString(ChatHistoryArchive(conversations = listOf(conversation))))
        val autoField = if (automatic) ",\"allowAutomaticStart\":true" else ""
        File(directory.parentFile, "pending.json").writeText("""{"id":"$plan","mode":"OVERWRITE_MATCHING","assistantId":"$assistant"$autoField}""")
        // Windows cannot load the APK's ARM tokenizer. Keep real Room/DAOs/transactions,
        // replacing only its native database factory and tokenizer table for this host test.
        dataSourceModule.single<AppDatabase> {
            databaseCreated = true
            Room.databaseBuilder(app, AppDatabase::class.java, "startup-test")
                .allowMainThreadQueries()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                    }
                }).build()
        }
        Shadow.directlyOn<Unit, RikkaHubApp>(app, RikkaHubApp::class.java, "onCreate")
    }

    @Implementation fun onTerminate() {
        try {
            Shadow.directlyOn<Unit, RikkaHubApp>(app, RikkaHubApp::class.java, "onTerminate")
        } finally {
            GlobalContext.getOrNull()?.getOrNull<AppDatabase>()?.close()
            org.koin.core.context.stopKoin()
        }
    }
}

@Implements(RikkaHubApp::class)
class AutomaticRestoreApplication : PendingRestoreApplication() {
    override val automatic = true
}

/** ARM native binaries cannot load in the Windows JVM; keep the rest of startup real. */
@Implements(className = "com.whl.quickjs.android.QuickJSLoader")
class HostQuickJsLoader {
    companion object {
        @Implementation @JvmStatic fun init() = Unit
    }
}
