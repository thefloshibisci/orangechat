package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.APP_DATABASE_MIGRATIONS
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.UsageTotals
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class UsageLedgerBackupTest {
    @Test
    fun `full database copy preflight and repeated restore preserve every ledger value and sequence`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val suffix = Uuid.random().toString().take(8)
        val sourceName = "src-$suffix"
        val restoredName = "dst-$suffix"
        val source = context.getDatabasePath(sourceName)
        val restored = context.getDatabasePath(restoredName)
        val config = " {\"maxTokens\":123,\"future\":true} "
        try {
            val original = open(sourceName)
            val originalRows: List<List<String?>>
            try {
                original.openHelper.writableDatabase.execSQL(
                    "INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at,conversation_config) VALUES('keep','title','[]',1,2,?)",
                    arrayOf(config),
                )
                original.usageRecordDao().insert(UsageRecordEntity(id = 17, createdAt = 1001, source = "CHAT",
                    providerId = "deleted-provider", modelId = "deleted-model", modelName = "snapshot API 名称",
                    assistantId = "removed-assistant", conversationId = "removed-chat", promptTokens = 20,
                    completionTokens = 7, cachedTokens = 8, totalTokens = 29, usageReported = true, outcome = "SUCCESS"))
                original.usageRecordDao().insert(UsageRecordEntity(createdAt = 1002, source = "TRANSLATION",
                    outcome = "ERROR"))
                originalRows = ledgerRows(original)
            } finally { original.close() }
            Files.copy(source.toPath(), restored.toPath())
            // The exact preflight used by full-backup restore upgrades/validates the staged database.
            repeat(2) {
                preflightBackupDatabase(context, restored, FrameworkSQLiteOpenHelperFactory())
                val db = open(restoredName)
                try {
                    assertEquals(43, db.openHelper.writableDatabase.version)
                    assertEquals(originalRows, ledgerRows(db))
                    assertEquals(UsageTotals(2, 20, 7, 8, 29, 1, 1), db.usageRecordDao().getTotals())
                    assertEquals(1001L, db.usageRecordDao().getFirstRecordTimestamp())
                    assertEquals(18L, db.usageRecordDao().observeLatestId().first())
                    db.openHelper.writableDatabase.query("SELECT conversation_config FROM ConversationEntity WHERE id='keep'").use { row ->
                        assertTrue(row.moveToFirst()); assertEquals(config, row.getString(0))
                    }
                    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { row -> assertFalse(row.moveToFirst()) }
                } finally { db.close() }
            }
            val db = open(restoredName)
            try {
                db.usageRecordDao().insert(UsageRecordEntity(createdAt = 1003, source = "TITLE", outcome = "CANCELLED"))
                assertEquals(19L, db.usageRecordDao().observeLatestId().first())
                assertEquals(UsageTotals(3, 20, 7, 8, 29, 2, 2), db.usageRecordDao().getTotals())
            } finally { db.close() }
            // Restoring and extending the copy must never modify the source backup database.
            val untouched = open(sourceName)
            try { assertEquals(originalRows, ledgerRows(untouched)) } finally { untouched.close() }
        } finally {
            context.deleteDatabase(sourceName)
            context.deleteDatabase(restoredName)
        }
    }

    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*APP_DATABASE_MIGRATIONS)
        .allowMainThreadQueries().build()

    private fun ledgerRows(db: AppDatabase): List<List<String?>> =
        db.openHelper.writableDatabase.query("SELECT * FROM usage_record ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                })
            }
        }
}
